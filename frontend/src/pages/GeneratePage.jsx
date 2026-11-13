import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { roomApi, seatingApi, studentApi, usageApi } from '../api/endpoints'
import {
  Alert,
  Badge,
  Card,
  Field,
  InfoTip,
  Loading,
  Stat,
} from '../components/ui'
import {
  IconAlert,
  IconBuilding,
  IconCheckCircle,
  IconInfo,
  IconSearch,
  IconUsers,
  IconWand,
} from '../components/Icons'

const STRICTNESS = [
  {
    value: 'NORMAL',
    label: 'Normal',
    badge: 'dH=1 · dV=0 · dD=0',
    hint: 'Separates bench-mates and horizontal side-by-side neighbours in the same row, with front-to-back column continuity.',
  },
  {
    value: 'INTERMEDIATE',
    label: 'Intermediate',
    badge: 'dH=1 · dV=1 · dD=0',
    hint: 'Separates same-cohort students horizontally (left/right) and vertically (directly in front or behind).',
  },
  {
    value: 'EXTREME',
    label: 'Extreme / Rigid',
    badge: 'dH=1 · dV=1 · dD=1',
    hint: 'Maximum 8-directional isolation — separates same-cohort students horizontally, vertically, and diagonally.',
  },
]

function normalizeKey(raw) {
  return (raw || '').trim().toLowerCase().replace(/[^a-z0-9]/g, '')
}

function humanizeKey(key) {
  if (!key) return ''
  return key
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .replace(/\b\w/g, (ch) => ch.toUpperCase())
}

function readAttr(student, key) {
  if (!student || !key) return ''
  const norm = normalizeKey(key)
  if (!norm) return ''
  if (norm === 'name') return student.name || ''
  const attrs = student.attributes || {}
  if (attrs[norm] !== undefined && attrs[norm] !== null) {
    return String(attrs[norm])
  }
  const matchedKey = Object.keys(attrs).find((k) => normalizeKey(k) === norm)
  return matchedKey ? String(attrs[matchedKey] ?? '') : ''
}

/** Matches SeatingService.categoryLabelOf(student, rawFilterAttribute) 1-to-1. */
function categoryLabelOf(student, rawFilterAttr) {
  const keys = String(rawFilterAttr || '')
    .split('+')
    .map((k) => normalizeKey(k))
    .filter(Boolean)
  if (!keys.length) return 'Unspecified'
  if (keys.length === 1) {
    const v = readAttr(student, keys[0]).trim()
    return v || 'Unspecified'
  }
  return keys
    .map((k) => {
      const v = readAttr(student, k).trim()
      return v || 'Unspecified'
    })
    .join(' · ')
}

export default function GeneratePage() {
  const navigate = useNavigate()
  const [rooms, setRooms] = useState([])
  const [students, setStudents] = useState([])
  const [totalStudentCount, setTotalStudentCount] = useState(0)
  const [identity, setIdentity] = useState(null)
  const [rosterSchema, setRosterSchema] = useState(null)
  const [usage, setUsage] = useState(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [error, setError] = useState(null)
  const [errorDetails, setErrorDetails] = useState([])
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)

  const [strictness, setStrictness] = useState('NORMAL')
  const [groupKeys, setGroupKeys] = useState([])
  const [groupMatchMode, setGroupMatchMode] = useState('ALL')
  const [scopeAttribute, setScopeAttribute] = useState('')
  const [scopeValue, setScopeValue] = useState('')
  const [clearExisting, setClearExisting] = useState(true)

  // Hall checkbox selection (initialized to all hall IDs on load)
  const [selectedRoomIds, setSelectedRoomIds] = useState([])

  // Category-based student selection:
  // filterAttribute = normalized key (e.g. 'branch' or 'department+semester')
  // selectedCategories = null means ALL categories checked; string[] means explicit subset
  const [filterAttribute, setFilterAttribute] = useState('')
  const [selectedCategories, setSelectedCategories] = useState(null)
  const [categorySearch, setCategorySearch] = useState('')

  // Live pre-flight feasibility & empty-seat analysis
  const [advisory, setAdvisory] = useState(null)
  const [analyzing, setAnalyzing] = useState(false)

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoadError(null)
      try {
        const [roomPage, studentPage, scheme, schema, usageSnap] = await Promise.all([
          roomApi.listAll({ sortBy: 'hallName' }),
          studentApi.listAll(),
          studentApi.identity().catch(() => null),
          studentApi.schema().catch(() => null),
          usageApi.usage().catch(() => null),
        ])
        if (cancelled) return

        const loadedRooms = roomPage?.content || []
        const loadedStudents = studentPage?.content || []
        const resolvedTotal = Math.max(
          Number(studentPage?.totalElements ?? 0),
          loadedStudents.length,
          Number(schema?.totalStudents ?? 0),
        )

        setRooms(loadedRooms)
        setSelectedRoomIds(loadedRooms.map((r) => r.id))
        setStudents(loadedStudents)
        setTotalStudentCount(resolvedTotal)
        setIdentity(scheme)
        setRosterSchema(schema)
        setUsage(usageSnap)

        const defaultGroups = scheme?.classmateAttributes?.length
          ? scheme.classmateAttributes
          : schema?.currentClassmateAttributes?.length
          ? schema.currentClassmateAttributes
          : schema?.suggestedClassmateGroups?.length
          ? schema.suggestedClassmateGroups[0].attributes
          : []

        let resolvedGroupKeys = []
        if (defaultGroups.length) {
          resolvedGroupKeys = defaultGroups.map((k) => normalizeKey(k)).filter(Boolean)
          setGroupKeys(resolvedGroupKeys)
        } else if (loadedStudents.length > 0) {
          const sampleKeys = Object.keys(loadedStudents[0]?.attributes || {})
            .map((k) => normalizeKey(k))
            .filter((k) => k && k !== 'name' && !k.includes('roll') && !k.endsWith('id'))
          if (sampleKeys.length > 0) {
            resolvedGroupKeys = [sampleKeys[0]]
            setGroupKeys(resolvedGroupKeys)
          }
        }

        // Auto-select default categorization attribute for Student Selection
        if (resolvedGroupKeys.length > 0) {
          setFilterAttribute(resolvedGroupKeys.join('+'))
        } else {
          const nonUniqueSchemaCol = (schema?.columns || []).find(
            (c) => normalizeKey(c.key) && normalizeKey(c.key) !== 'name' && !c.unique,
          )
          if (nonUniqueSchemaCol) {
            setFilterAttribute(normalizeKey(nonUniqueSchemaCol.key))
          }
        }

        const resolvedScope = scheme?.scopeAttribute || schema?.currentScopeAttribute || ''
        if (resolvedScope) {
          setScopeAttribute(normalizeKey(resolvedScope))
        }
      } catch (failure) {
        if (!cancelled) setLoadError(failure.message)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    load()
    return () => {
      cancelled = true
    }
  }, [])

  // Synthesize attribute definitions from server schema, falling back to live student records
  const availableAttributes = useMemo(() => {
    const schemaCols = (rosterSchema?.columns || [])
      .map((col) => ({
        ...col,
        key: normalizeKey(col.key),
        title: col.title || humanizeKey(col.key),
      }))
      .filter((col) => col.key && col.key !== 'name')

    if (schemaCols.length > 0) return schemaCols

    const valueSets = new Map()
    students.forEach((student) => {
      Object.entries(student?.attributes || {}).forEach(([rawKey, val]) => {
        const norm = normalizeKey(rawKey)
        if (!norm || norm === 'name') return
        if (!valueSets.has(norm)) valueSets.set(norm, { rawKey, values: new Set() })
        if (val !== null && val !== undefined && String(val).trim() !== '') {
          valueSets.get(norm).values.add(String(val).trim())
        }
      })
    })
    return [...valueSets.entries()].map(([key, info]) => ({
      key,
      title: humanizeKey(info.rawKey || key),
      distinctValues: info.values.size,
      unique: students.length > 1 && info.values.size === students.length,
    }))
  }, [rosterSchema, students])

  const titleByKey = useMemo(() => {
    const map = new Map()
    availableAttributes.forEach((col) => {
      map.set(col.key, col.title || col.key)
    })
    return map
  }, [availableAttributes])

  // Options for the "Categorize Students By" selector (composite cohort + individual attributes)
  const categorizationOptions = useMemo(() => {
    const nonUnique = availableAttributes.filter((a) => !a.unique)
    const baseCols = nonUnique.length > 0 ? nonUnique : availableAttributes
    const options = []
    if (groupKeys.length > 1) {
      const compositeKey = groupKeys.join('+')
      const compositeTitle = groupKeys
        .map((k) => titleByKey.get(k) || humanizeKey(k))
        .join(' + ')
      options.push({
        value: compositeKey,
        label: `${compositeTitle} (Combined Cohort)`,
      })
    }
    baseCols.forEach((col) => {
      options.push({
        value: col.key,
        label: `${col.title || humanizeKey(col.key)}${
          col.distinctValues ? ` (${col.distinctValues} groups)` : ''
        }`,
      })
    })
    return options
  }, [availableAttributes, groupKeys, titleByKey])

  // Ensure filterAttribute stays valid if options initialize after render
  useEffect(() => {
    if (!filterAttribute && categorizationOptions.length > 0) {
      setFilterAttribute(categorizationOptions[0].value)
      setSelectedCategories(null)
    }
  }, [filterAttribute, categorizationOptions])

  const scopeValueOptions = useMemo(() => {
    if (!scopeAttribute) return []
    const dict = rosterSchema?.distinctValuesByAttribute || {}
    const fromSchema =
      dict[scopeAttribute] ||
      dict[Object.keys(dict).find((k) => normalizeKey(k) === normalizeKey(scopeAttribute))]
    if (fromSchema?.length) return fromSchema
    const found = new Set()
    students.forEach((s) => {
      const v = readAttr(s, scopeAttribute)
      if (v) found.add(v)
    })
    return [...found].sort()
  }, [scopeAttribute, rosterSchema, students])

  // Students matching the optional Exam Round filter (before category checkbox selection)
  const roundScopedStudents = useMemo(() => {
    if (!scopeAttribute || !scopeValue) return students
    const target = scopeValue.trim().toLowerCase()
    return students.filter((s) => readAttr(s, scopeAttribute).trim().toLowerCase() === target)
  }, [students, scopeAttribute, scopeValue])

  // Automatically extracted student category groups with live headcounts
  const categoryGroups = useMemo(() => {
    if (!filterAttribute || roundScopedStudents.length === 0) return []
    const counts = new Map()
    roundScopedStudents.forEach((s) => {
      const label = categoryLabelOf(s, filterAttribute)
      counts.set(label, (counts.get(label) || 0) + 1)
    })
    return [...counts.entries()]
      .sort((a, b) => a[0].localeCompare(b[0], undefined, { numeric: true }))
      .map(([label, count]) => ({ label, count }))
  }, [filterAttribute, roundScopedStudents])

  const visibleCategoryGroups = useMemo(() => {
    const q = categorySearch.trim().toLowerCase()
    if (!q) return categoryGroups
    return categoryGroups.filter((g) => g.label.toLowerCase().includes(q))
  }, [categoryGroups, categorySearch])

  const effectiveSelectedCategories = useMemo(() => {
    if (selectedCategories === null) {
      return categoryGroups.map((g) => g.label)
    }
    const validLabels = new Set(categoryGroups.map((g) => g.label))
    return selectedCategories.filter((c) => validLabels.has(c))
  }, [selectedCategories, categoryGroups])

  const selectedCategorySet = useMemo(
    () => new Set(effectiveSelectedCategories.map((c) => c.toLowerCase())),
    [effectiveSelectedCategories],
  )

  const isCategorySubset =
    categoryGroups.length > 0 &&
    effectiveSelectedCategories.length < categoryGroups.length

  // Final list of students selected for seating generation
  const inScopeStudents = useMemo(() => {
    if (!filterAttribute || categoryGroups.length === 0) {
      return roundScopedStudents
    }
    if (selectedCategories === null) {
      return roundScopedStudents
    }
    if (effectiveSelectedCategories.length === 0) {
      return []
    }
    return roundScopedStudents.filter((s) =>
      selectedCategorySet.has(categoryLabelOf(s, filterAttribute).toLowerCase()),
    )
  }, [
    roundScopedStudents,
    filterAttribute,
    categoryGroups.length,
    selectedCategories,
    effectiveSelectedCategories.length,
    selectedCategorySet,
  ])

  const inScopeCount =
    !scopeAttribute && !scopeValue && !isCategorySubset && selectedCategories === null
      ? Math.max(inScopeStudents.length, totalStudentCount)
      : inScopeStudents.length

  const activeRooms = useMemo(
    () => rooms.filter((room) => selectedRoomIds.includes(room.id)),
    [rooms, selectedRoomIds],
  )

  const totalBenches = useMemo(
    () => activeRooms.reduce((sum, room) => sum + room.rows * room.columns, 0),
    [activeRooms],
  )

  const totalSeats = useMemo(
    () =>
      activeRooms.reduce(
        (total, room) =>
          total + room.rows * room.columns * (room.benchType === 'DUAL_SEATER' ? 2 : 1),
        0,
      ),
    [activeRooms],
  )

  // Live pre-flight feasibility & empty-seat check for ONLY the checked categories & checked halls
  useEffect(() => {
    if (
      loading ||
      inScopeCount === 0 ||
      activeRooms.length === 0
    ) {
      setAdvisory(null)
      return undefined
    }
    let cancelled = false
    const timer = setTimeout(async () => {
      setAnalyzing(true)
      try {
        const payload = {
          strictness,
          groupMatchMode,
          scopeAttribute: scopeValue ? scopeAttribute || null : null,
          scopeValue: scopeValue || null,
          roomIds: selectedRoomIds,
          clearExisting,
          filterAttribute: isCategorySubset ? filterAttribute : null,
          filterValues: isCategorySubset ? effectiveSelectedCategories : null,
        }
        if (groupKeys.length) {
          payload.groupAttributes = groupKeys
        }
        const adv = await seatingApi.analyze(payload)
        if (!cancelled) setAdvisory(adv)
      } catch {
        if (!cancelled) setAdvisory(null)
      } finally {
        if (!cancelled) setAnalyzing(false)
      }
    }, 180)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [
    loading,
    inScopeCount,
    activeRooms.length,
    strictness,
    groupKeys,
    groupMatchMode,
    scopeAttribute,
    scopeValue,
    clearExisting,
    selectedRoomIds,
    isCategorySubset,
    filterAttribute,
    effectiveSelectedCategories,
  ])

  const cohortSummary = useMemo(() => {
    if (!groupKeys.length || !inScopeStudents.length) return null
    const counts = new Map()
    inScopeStudents.forEach((s) => {
      const sig = groupKeys.map((k) => readAttr(s, k) || '—').join(' · ')
      counts.set(sig, (counts.get(sig) || 0) + 1)
    })
    const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1])
    return {
      cohortCount: sorted.length,
      largestLabel: sorted[0]?.[0] || '',
      largestCount: sorted[0]?.[1] || 0,
      batches: sorted.slice(0, 8).map(([label, count]) => ({ label, count })),
    }
  }, [groupKeys, inScopeStudents])

  const planSeatLimit = usage?.seatsPerGeneration ?? null
  const planGenUsed = usage?.generationsUsed ?? null
  const planGenLimit = usage?.generationsLimit ?? null
  const exceedsPlanSeats = planSeatLimit !== null && inScopeCount > planSeatLimit
  const exceedsHallSeats = activeRooms.length > 0 && inScopeCount > totalSeats
  const exceedsMonthlyGens =
    planGenLimit !== null && planGenUsed !== null && planGenUsed >= planGenLimit

  function toggleCategory(label) {
    const allLabels = categoryGroups.map((g) => g.label)
    const current =
      selectedCategories === null ? allLabels : effectiveSelectedCategories
    const exists = current.includes(label)
    const next = exists
      ? current.filter((item) => item !== label)
      : [...current, label]
    if (next.length === allLabels.length) {
      setSelectedCategories(null)
    } else {
      setSelectedCategories(next)
    }
  }

  function toggleRoom(id) {
    setSelectedRoomIds((current) =>
      current.includes(id) ? current.filter((item) => item !== id) : [...current, id],
    )
  }

  function toggleGroup(key) {
    const norm = normalizeKey(key)
    setGroupKeys((current) =>
      current.includes(norm) ? current.filter((item) => item !== norm) : [...current, norm],
    )
  }

  async function generate() {
    if (inScopeCount === 0) {
      setError('Select at least one student category/group to generate a seating plan.')
      return
    }
    if (activeRooms.length === 0) {
      setError('Select at least one examination hall to generate a seating plan.')
      return
    }
    setError(null)
    setErrorDetails([])
    setBusy(true)
    setResult(null)
    try {
      const payload = {
        strictness,
        groupMatchMode,
        scopeAttribute: scopeValue ? scopeAttribute || null : null,
        scopeValue: scopeValue || null,
        roomIds: selectedRoomIds,
        clearExisting,
        filterAttribute: isCategorySubset ? filterAttribute : null,
        filterValues: isCategorySubset ? effectiveSelectedCategories : null,
      }
      if (groupKeys.length) {
        payload.groupAttributes = groupKeys
      }
      const outcome = await seatingApi.arrange(payload)
      setResult(outcome)
      if (outcome?.advisory) {
        setAdvisory(outcome.advisory)
      }
      usageApi
        .usage()
        .then((u) => setUsage(u))
        .catch(() => {})
    } catch (failure) {
      setError(failure.message)
      setErrorDetails(Array.isArray(failure.details) ? failure.details : [])
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <Loading label="Loading roster schema and halls…" />

  const groupLabels = groupKeys.map((k) => titleByKey.get(k) || humanizeKey(k))
  const selectedStrictnessObj = STRICTNESS.find((s) => s.value === strictness) || STRICTNESS[0]
  const maxBatchSeats = advisory?.maxCohortSeatsInCurrentHalls ?? null
  const filterTitle = filterAttribute
    ? filterAttribute
        .split('+')
        .map((k) => titleByKey.get(k) || humanizeKey(k))
        .join(' + ')
    : 'Category'

  const canGenerate =
    !busy &&
    totalStudentCount > 0 &&
    rooms.length > 0 &&
    inScopeCount > 0 &&
    activeRooms.length > 0

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Generate Seating Arrangement</h1>
          <p>
            <strong>{inScopeCount.toLocaleString()}</strong> of{' '}
            {totalStudentCount.toLocaleString()} student
            {totalStudentCount === 1 ? '' : 's'} selected ·{' '}
            <strong>{activeRooms.length}</strong> of {rooms.length} hall
            {rooms.length === 1 ? '' : 's'} selected (
            <strong>{totalSeats.toLocaleString()}</strong> seats across{' '}
            {totalBenches.toLocaleString()} benches)
          </p>
        </div>
        <button
          type="button"
          className="btn btn--primary btn--lg"
          onClick={generate}
          disabled={!canGenerate}
        >
          {busy ? <span className="spinner" /> : <IconWand width={17} height={17} />}
          {busy ? 'Solving Arrangement…' : 'Generate Seating Plan'}
        </button>
      </div>

      <div className="stack">
        {loadError && (
          <Alert tone="error" title="Unable to Load Roster or Hall Data:">
            <div>{loadError}</div>
          </Alert>
        )}

        {error && (
          <Alert tone="error" title="Arrangement Could Not Be Generated:">
            <div>{error}</div>
            {errorDetails.length > 0 && (
              <ul className="alert__list">
                {errorDetails.map((d, idx) => (
                  <li key={idx}>{d}</li>
                ))}
              </ul>
            )}
          </Alert>
        )}

        {totalStudentCount === 0 && !loadError && (
          <Alert tone="warning" title="No students on the roster yet.">
            <Link to="/students">Upload an Excel sheet or add students</Link> before generating.
          </Alert>
        )}
        {rooms.length === 0 && !loadError && (
          <Alert tone="warning" title="No exam halls configured yet.">
            <Link to="/halls">Create or import examination halls</Link> before generating.
          </Alert>
        )}

        {/* Prominent Seating Capacity & Batch Allocation Limits Panel */}
        {(totalStudentCount > 0 || rooms.length > 0) && (
          <div
            className={
              'capacity-overview' +
              (inScopeCount === 0 ||
              activeRooms.length === 0 ||
              exceedsHallSeats ||
              exceedsPlanSeats ||
              exceedsMonthlyGens ||
              (advisory && !advisory.feasible)
                ? ' capacity-overview--alert'
                : advisory?.requiresEmptySeats
                ? ' capacity-overview--warn'
                : '')
            }
          >
            <div className="capacity-overview__head">
              <div className="capacity-overview__title">
                <span className="capacity-overview__icon">
                  <IconInfo width={18} height={18} />
                </span>
                <div>
                  <h2>Seating Capacity &amp; Batch Allocation Limits</h2>
                  <p>
                    Live comparison of your selected student categories, selected hall capacity,
                    subscription seat cap, and anti-copying separation buffers.
                  </p>
                </div>
              </div>
              <div className="row" style={{ gap: 8 }}>
                {totalStudentCount > 0 && rooms.length > 0 && (
                  <Badge
                    tone={
                      inScopeCount === 0 ||
                      activeRooms.length === 0 ||
                      exceedsHallSeats ||
                      exceedsPlanSeats ||
                      (advisory && !advisory.feasible)
                        ? 'danger'
                        : advisory?.requiresEmptySeats
                        ? 'warning'
                        : 'success'
                    }
                  >
                    {inScopeCount === 0
                      ? 'No Students Selected'
                      : activeRooms.length === 0
                      ? 'No Halls Selected'
                      : exceedsHallSeats
                      ? 'Hall Capacity Exceeded'
                      : exceedsPlanSeats
                      ? 'Plan Seat Limit Exceeded'
                      : advisory && !advisory.feasible
                      ? 'Insufficient Batch Separation Capacity'
                      : advisory?.requiresEmptySeats
                      ? 'Valid · Vacant Buffer Seats Active'
                      : 'Data Ready · Capacity Sufficient'}
                  </Badge>
                )}
              </div>
            </div>

            <div className="grid grid--4" style={{ marginTop: 14 }}>
              <Stat
                label="Selected Students"
                value={inScopeCount.toLocaleString()}
                limit={totalSeats > 0 ? totalSeats : undefined}
                unit=" seats"
                accent={exceedsHallSeats || inScopeCount === 0 ? 'amber' : 'brand'}
                hint={
                  isCategorySubset
                    ? `${effectiveSelectedCategories.length} of ${categoryGroups.length} ${filterTitle} groups`
                    : scopeValue
                    ? `Filtered by ${titleByKey.get(scopeAttribute) || scopeAttribute}: ${scopeValue}`
                    : `All ${totalStudentCount.toLocaleString()} uploaded students`
                }
              />
              <Stat
                label="Selected Hall Capacity"
                value={totalSeats.toLocaleString()}
                accent={exceedsHallSeats || activeRooms.length === 0 ? 'amber' : 'emerald'}
                hint={`${activeRooms.length} of ${rooms.length} hall${
                  rooms.length === 1 ? '' : 's'
                } · ${totalBenches.toLocaleString()} benches`}
              />
              <Stat
                label="Plan Max Seats / Gen"
                value={planSeatLimit !== null ? planSeatLimit.toLocaleString() : '—'}
                accent={exceedsPlanSeats ? 'amber' : 'violet'}
                hint={
                  planGenLimit !== null
                    ? `${planGenUsed ?? 0} / ${planGenLimit} monthly generations used`
                    : 'Active workspace quota'
                }
              />
              <Stat
                label="Largest Selected Batch"
                value={cohortSummary ? cohortSummary.largestCount.toLocaleString() : '—'}
                limit={maxBatchSeats && maxBatchSeats > 0 ? maxBatchSeats : undefined}
                unit={maxBatchSeats ? ' max' : ''}
                accent={
                  maxBatchSeats && cohortSummary && cohortSummary.largestCount > maxBatchSeats
                    ? 'amber'
                    : 'brand'
                }
                hint={
                  cohortSummary
                    ? `${cohortSummary.largestLabel} (${cohortSummary.cohortCount} cohorts)`
                    : 'Select student categories below'
                }
              />
            </div>

            <div className="capacity-rules-callout">
              <div className="capacity-rules-callout__item">
                <strong>Maximum Seating Capacity Limit:</strong> Selected halls provide{' '}
                <strong>{totalSeats.toLocaleString()} physical seats</strong> for{' '}
                <strong>{inScopeCount.toLocaleString()} selected students</strong>
                {planSeatLimit !== null && (
                  <>
                    {' '}
                    (plan ceiling: <strong>{planSeatLimit.toLocaleString()} seats/generation</strong>)
                  </>
                )}
                .{' '}
                {exceedsHallSeats && (
                  <span className="capacity-rules-callout__alert">
                    Currently {inScopeCount - totalSeats} student(s) exceed selected hall seats —
                    check another hall below, uncheck a student group, or{' '}
                    <Link to="/halls">add more halls</Link>.
                  </span>
                )}
                {exceedsPlanSeats && (
                  <span className="capacity-rules-callout__alert">
                    Currently {inScopeCount - planSeatLimit} student(s) exceed your plan&apos;s
                    per-generation seat cap — uncheck a student group or{' '}
                    <Link to="/settings">upgrade your plan</Link>.
                  </span>
                )}
              </div>
              <div className="capacity-rules-callout__item">
                <strong>Batch Size Overflow &amp; Vacant Buffer Seats:</strong> Under{' '}
                <strong>{selectedStrictnessObj.label}</strong> separation, classmates from the same
                cohort cannot sit in forbidden adjacent positions
                {maxBatchSeats !== null
                  ? ` (max ${maxBatchSeats} seats for a single batch in selected halls)`
                  : ''}
                . When one batch outnumbers others, benches intentionally leave vacant buffer seats
                rather than placing classmates side by side.
              </div>
            </div>

            {cohortSummary?.batches?.length > 0 && (
              <div className="cohort-strip">
                <span className="cohort-strip__label">Selected Batch Sizes:</span>
                <div className="cohort-strip__pills">
                  {cohortSummary.batches.map((b) => {
                    const overCap = maxBatchSeats !== null && b.count > maxBatchSeats
                    return (
                      <span
                        key={b.label}
                        className={`cohort-pill ${overCap ? 'cohort-pill--over' : ''}`}
                        title={
                          overCap
                            ? `Exceeds max ${maxBatchSeats} conflict-free seats in selected halls`
                            : `${b.count} students in ${b.label}`
                        }
                      >
                        <span>{b.label}</span>
                        <strong>{b.count}</strong>
                        {maxBatchSeats !== null && <small>/ {maxBatchSeats}</small>}
                      </span>
                    )
                  })}
                </div>
              </div>
            )}
          </div>
        )}

        {/* Live Pre-Flight Feasibility & Mandatory Empty-Seat Diagnostic Banner */}
        {advisory && inScopeCount > 0 && activeRooms.length > 0 && (
          <AdvisoryBanner advisory={advisory} analyzing={analyzing} />
        )}

        {/* STEP 1: FLEXIBLE STUDENT CATEGORY / GROUP CHECKBOX SELECTION */}
        <Card
          title="1. Select Student Categories / Groups"
          subtitle={
            categoryGroups.length > 0
              ? `Automatically grouped from your uploaded roster by ${filterTitle} — check the classes, branches, or categories to seat in this generation.`
              : `All ${totalStudentCount.toLocaleString()} uploaded students are included.`
          }
          accent="brand"
          actions={
            categoryGroups.length > 0 && (
              <div className="btn-row">
                <Badge tone={inScopeCount > 0 ? 'brand' : 'danger'}>
                  {inScopeCount.toLocaleString()} / {roundScopedStudents.length.toLocaleString()} students
                </Badge>
                <button
                  type="button"
                  className="btn btn--sm"
                  onClick={() => setSelectedCategories(null)}
                  disabled={selectedCategories === null}
                >
                  Select All ({categoryGroups.length})
                </button>
                <button
                  type="button"
                  className="btn btn--sm"
                  onClick={() => setSelectedCategories([])}
                  disabled={effectiveSelectedCategories.length === 0}
                >
                  Clear Selection
                </button>
              </div>
            )
          }
        >
          {totalStudentCount === 0 ? (
            <p className="small muted" style={{ margin: 0 }}>
              Upload a student Excel roster on the <Link to="/students">Students</Link> page to
              automatically extract classes, branches, and categories.
            </p>
          ) : categorizationOptions.length === 0 ? (
            <p className="small muted" style={{ margin: 0 }}>
              All <strong>{totalStudentCount.toLocaleString()}</strong> students will be included.
              Upload a spreadsheet with Class, Branch, Department, or Category columns to filter by
              student groups.
            </p>
          ) : (
            <div className="stack">
              <div className="selection-controls-bar">
                <div style={{ minWidth: 240, flex: '1 1 260px', maxWidth: 420 }}>
                  <Field
                    label="Group / Categorize Uploaded Students By"
                    hint="Switch between any extracted Excel column or combined cohort."
                  >
                    <select
                      value={filterAttribute}
                      onChange={(e) => {
                        setFilterAttribute(e.target.value)
                        setSelectedCategories(null)
                        setCategorySearch('')
                      }}
                    >
                      {categorizationOptions.map((opt) => (
                        <option key={opt.value} value={opt.value}>
                          {opt.label}
                        </option>
                      ))}
                    </select>
                  </Field>
                </div>

                {categoryGroups.length > 6 && (
                  <div style={{ minWidth: 200, flex: '1 1 220px', maxWidth: 320 }}>
                    <Field label="Filter Category List" hint="Quickly find a class or branch">
                      <div className="search-input-wrap" style={{ width: '100%' }}>
                        <IconSearch width={15} height={15} />
                        <input
                          type="search"
                          value={categorySearch}
                          onChange={(e) => setCategorySearch(e.target.value)}
                          placeholder={`Search ${filterTitle}…`}
                        />
                      </div>
                    </Field>
                  </div>
                )}
              </div>

              {effectiveSelectedCategories.length === 0 && (
                <Alert tone="warning" title="No Student Categories Selected:">
                  Check one or more <strong>{filterTitle}</strong> groups below (or click{' '}
                  <strong>Select All</strong>) to include students in this seating arrangement.
                </Alert>
              )}

              <div className="selection-grid">
                {visibleCategoryGroups.map((group) => {
                  const checked =
                    selectedCategories === null ||
                    effectiveSelectedCategories.includes(group.label)
                  return (
                    <label
                      key={group.label}
                      className={`selection-tile ${
                        checked ? 'selection-tile--selected' : ''
                      }`}
                    >
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={() => toggleCategory(group.label)}
                      />
                      <div className="selection-tile__body">
                        <div className="selection-tile__title">{group.label}</div>
                        <div className="selection-tile__meta">
                          <IconUsers width={13} height={13} />
                          <span>
                            <strong>{group.count.toLocaleString()}</strong>{' '}
                            {group.count === 1 ? 'student' : 'students'}
                          </span>
                        </div>
                      </div>
                    </label>
                  )
                })}
              </div>
            </div>
          )}
        </Card>

        {/* STEP 2: EXAMINATION HALL CHECKBOX SELECTION */}
        <Card
          title="2. Select Examination Halls"
          subtitle={
            activeRooms.length === 0
              ? 'No examination halls selected — check at least one hall below.'
              : `Using ${activeRooms.length} of ${rooms.length} hall(s) (${totalSeats.toLocaleString()} physical seats for ${inScopeCount.toLocaleString()} selected students).`
          }
          accent="emerald"
          actions={
            rooms.length > 0 && (
              <div className="btn-row">
                {activeRooms.length > 0 && inScopeCount > 0 && (
                  <Badge tone={totalSeats >= inScopeCount ? 'success' : 'danger'}>
                    {totalSeats >= inScopeCount
                      ? `${(totalSeats - inScopeCount).toLocaleString()} spare seats`
                      : `${(inScopeCount - totalSeats).toLocaleString()} seats short`}
                  </Badge>
                )}
                <button
                  type="button"
                  className="btn btn--sm"
                  onClick={() => setSelectedRoomIds(rooms.map((room) => room.id))}
                  disabled={selectedRoomIds.length === rooms.length}
                >
                  Select All Halls ({rooms.length})
                </button>
                <button
                  type="button"
                  className="btn btn--sm"
                  onClick={() => setSelectedRoomIds([])}
                  disabled={selectedRoomIds.length === 0}
                >
                  Clear Selection
                </button>
              </div>
            )
          }
        >
          {rooms.length === 0 ? (
            <p className="small muted" style={{ margin: 0 }}>
              No halls configured yet. <Link to="/halls">Add or import examination halls</Link> to
              continue.
            </p>
          ) : (
            <div className="stack">
              {activeRooms.length === 0 && (
                <Alert tone="warning" title="No Examination Halls Selected:">
                  Check one or more examination halls below to allocate seats into.
                </Alert>
              )}
              <div className="selection-grid selection-grid--halls">
                {rooms.map((room) => {
                  const cap =
                    room.rows * room.columns * (room.benchType === 'DUAL_SEATER' ? 2 : 1)
                  const selected = selectedRoomIds.includes(room.id)
                  return (
                    <label
                      key={room.id}
                      className={`selection-tile ${
                        selected ? 'selection-tile--selected' : ''
                      }`}
                    >
                      <input
                        type="checkbox"
                        checked={selected}
                        onChange={() => toggleRoom(room.id)}
                      />
                      <div className="selection-tile__body">
                        <div className="row row--between" style={{ gap: 8 }}>
                          <span className="selection-tile__title">{room.hallName}</span>
                          <Badge tone={selected ? 'brand' : 'default'}>{cap} seats</Badge>
                        </div>
                        <div className="selection-tile__meta">
                          <IconBuilding width={13} height={13} />
                          <span>
                            {room.rows} × {room.columns} grid ·{' '}
                            {room.benchType === 'DUAL_SEATER' ? 'Dual bench (2)' : 'Single seat (1)'}
                          </span>
                        </div>
                      </div>
                    </label>
                  )
                })}
              </div>
            </div>
          )}
        </Card>

        {/* STEPS 3 & 4: SEPARATION DIFFICULTY & COHORT ATTRIBUTES */}
        <div className="grid grid--2">
          <Card
            title="3. Separation Difficulty & Plan Mode"
            subtitle="Choose how strictly classmates are separated across seats and rows."
            accent="brand"
          >
            <Field
              label="Separation Difficulty"
              hint="Rules are enforced strictly — the solver never silently relaxes separation constraints."
              info={
                <InfoTip title="Understanding Difficulty Levels & Empty Buffer Seats">
                  <p style={{ marginBottom: 6 }}>
                    <strong>Normal:</strong> Keeps classmates off the same dual bench and apart
                    horizontally within a row.
                  </p>
                  <p style={{ marginBottom: 6 }}>
                    <strong>Intermediate:</strong> Separates classmates both horizontally (left/right)
                    and vertically (front/back).
                  </p>
                  <p style={{ marginBottom: 6 }}>
                    <strong>Extreme / Rigid:</strong> Full 8-way surrounding isolation (horizontal,
                    vertical, and diagonal).
                  </p>
                  <p style={{ marginBottom: 0 }}>
                    <strong>Cohort Shortage &amp; Empty Seats:</strong> When one student group is
                    larger than the remaining groups combined, the engine automatically leaves
                    empty buffer seats rather than violating separation rules.
                  </p>
                </InfoTip>
              }
            >
              <div className="stack stack--tight">
                {STRICTNESS.map((option) => (
                  <label
                    key={option.value}
                    className={`identity-option ${
                      strictness === option.value ? 'identity-option--selected' : ''
                    }`}
                  >
                    <input
                      type="radio"
                      name="strictness"
                      checked={strictness === option.value}
                      onChange={() => setStrictness(option.value)}
                    />
                    <span className="spread" style={{ flex: 1 }}>
                      <span className="row row--between">
                        <strong>{option.label}</strong>
                        <span className="mono small faint">{option.badge}</span>
                      </span>
                      <span className="small muted">{option.hint}</span>
                    </span>
                  </label>
                ))}
              </div>
            </Field>

            <details className="custom-limits-expander" open={Boolean(scopeValue)}>
              <summary>Optional Multi-Round Hall Scope (Exam Date / Shift / Paper)</summary>
              <div className="grid grid--2" style={{ marginTop: 12 }}>
                <Field
                  label="Exam Round Field"
                  hint="Filter by Exam Date, Paper, Shift, etc."
                >
                  <select
                    value={scopeAttribute}
                    onChange={(e) => {
                      setScopeAttribute(e.target.value)
                      setScopeValue('')
                    }}
                  >
                    <option value="">All rounds</option>
                    {availableAttributes
                      .filter((a) => !a.unique)
                      .map((a) => (
                        <option key={a.key} value={a.key}>
                          {a.title}
                        </option>
                      ))}
                  </select>
                </Field>

                <Field
                  label="Round Value"
                  hint={scopeAttribute ? 'Choose which round to seat' : 'Select a round field first'}
                >
                  <select
                    value={scopeValue}
                    disabled={!scopeAttribute}
                    onChange={(e) => setScopeValue(e.target.value)}
                  >
                    <option value="">All values ({totalStudentCount} students)</option>
                    {scopeValueOptions.map((val) => (
                      <option key={val} value={val}>
                        {val}
                      </option>
                    ))}
                  </select>
                </Field>
              </div>
            </details>

            {groupKeys.length > 0 && (
              <div className="rule-callout" style={{ marginTop: 12 }}>
                <div className="strong small" style={{ marginBottom: 4 }}>
                  Active Separation Rule
                </div>
                <div className="small">
                  Students sharing{' '}
                  <strong>
                    {groupMatchMode === 'ANY' ? 'any of ' : ''}
                    {groupLabels.join(' + ')}
                  </strong>{' '}
                  will be separated under <strong>{selectedStrictnessObj.label}</strong> geometry.
                </div>
                {cohortSummary && (
                  <div className="faint small" style={{ marginTop: 4 }}>
                    {inScopeCount} selected students · {cohortSummary.cohortCount} cohorts · largest:{' '}
                    {cohortSummary.largestLabel} ({cohortSummary.largestCount})
                  </div>
                )}
              </div>
            )}

            <label className="check" style={{ marginTop: 12 }}>
              <input
                type="checkbox"
                checked={clearExisting}
                onChange={(event) => setClearExisting(event.target.checked)}
              />
              <span>
                {scopeValue ? (
                  <>
                    <strong>Replace existing allocations for &apos;{scopeValue}&apos;</strong>{' '}
                    (other rounds are preserved)
                  </>
                ) : clearExisting ? (
                  <>
                    <strong>Replace entire current seating plan</strong> with this generation
                  </>
                ) : (
                  <>
                    <strong>Incremental mode:</strong> preserve already-seated halls/groups and
                    place selected categories into free seats
                  </>
                )}
              </span>
            </label>
          </Card>

          <Card
            title="4. Anti-Copying Classmate Attributes"
            subtitle="Select which roster columns define classmates who must NOT sit adjacent."
            actions={
              <InfoTip title="Dynamic Cohort Grouping">
                Pick the columns that identify students taking the same exam or belonging to the
                same class/branch (for example Grade + Section, or Department + Semester). No
                institution-specific column names are hardcoded.
              </InfoTip>
            }
          >
            {identity?.classmateAttributes?.length > 0 && (
              <div style={{ marginBottom: 12 }} className="row row--between">
                <div>
                  <span className="small muted">Saved institution default: </span>
                  <Badge tone="brand">
                    {identity.classmateAttributes
                      .map((k) => titleByKey.get(normalizeKey(k)) || humanizeKey(k))
                      .join(' + ')}
                  </Badge>
                </div>
                <button
                  type="button"
                  className="btn btn--sm"
                  onClick={() =>
                    setGroupKeys(identity.classmateAttributes.map((k) => normalizeKey(k)))
                  }
                >
                  Use default
                </button>
              </div>
            )}

            {(rosterSchema?.suggestedClassmateGroups || []).length > 0 && (
              <div style={{ marginBottom: 12 }}>
                <div className="small muted" style={{ marginBottom: 6 }}>
                  Suggested cohorts:
                </div>
                <div className="btn-row">
                  {rosterSchema.suggestedClassmateGroups.map((preset) => {
                    const normAttrs = preset.attributes.map((k) => normalizeKey(k))
                    const active =
                      normAttrs.length === groupKeys.length &&
                      normAttrs.every((k) => groupKeys.includes(k))
                    return (
                      <button
                        key={normAttrs.join('+')}
                        type="button"
                        className={`btn btn--sm ${active ? 'btn--primary' : ''}`}
                        onClick={() => setGroupKeys(normAttrs)}
                      >
                        {preset.label}
                      </button>
                    )
                  })}
                </div>
              </div>
            )}

            {availableAttributes.length === 0 ? (
              <p className="small muted">Upload or add students first to inspect roster attributes.</p>
            ) : (
              <>
                <div className="stack stack--tight">
                  {availableAttributes.map((attribute) => (
                    <label
                      key={attribute.key}
                      className={`identity-option ${
                        groupKeys.includes(attribute.key) ? 'identity-option--selected' : ''
                      }`}
                    >
                      <input
                        type="checkbox"
                        checked={groupKeys.includes(attribute.key)}
                        onChange={() => toggleGroup(attribute.key)}
                      />
                      <span className="row row--between" style={{ flex: 1 }}>
                        <strong>{attribute.title || attribute.key}</strong>
                        <span className="small muted">{attribute.distinctValues} distinct</span>
                      </span>
                    </label>
                  ))}
                </div>
                {groupKeys.length > 1 && (
                  <Field label="Conflict Matching Mode" style={{ marginTop: 12 }}>
                    <select
                      value={groupMatchMode}
                      onChange={(event) => setGroupMatchMode(event.target.value)}
                    >
                      <option value="ALL">
                        Share ALL selected attributes (exact classmates — recommended)
                      </option>
                      <option value="ANY">
                        Share ANY selected attribute (strict cross-attribute isolation)
                      </option>
                    </select>
                  </Field>
                )}
              </>
            )}
          </Card>
        </div>

        {result && (
          <Card title="Generation Summary" accent="emerald">
            <div className="grid grid--4">
              <Stat label="Students Seated" value={result.seated} accent="emerald" />
              <Stat
                label="Vacant / Buffer Seats in Used Halls"
                value={Math.max(0, result.totalSlots - result.seated)}
                accent="amber"
              />
              <Stat label="Hall Seat Capacity" value={result.totalSlots} accent="brand" />
              <div className="stat stat--emerald">
                <div className="stat__label">Compliance</div>
                <div className="stat__value" style={{ fontSize: 18, marginTop: 10 }}>
                  {result.solved ? (
                    <Badge tone="success">100% Rule-Compliant</Badge>
                  ) : (
                    <Badge tone="warning">Partial</Badge>
                  )}
                </div>
                <div className="stat__hint">Zero silent rule relaxation</div>
              </div>
            </div>

            <div className="btn-row" style={{ marginTop: 16 }}>
              <button type="button" className="btn btn--primary" onClick={() => navigate('/plan')}>
                Inspect Interactive Seating Plan
              </button>
              <button type="button" className="btn" onClick={() => navigate('/exports')}>
                Download Excel &amp; PDF Sheets
              </button>
            </div>
          </Card>
        )}
      </div>
    </>
  )
}

function AdvisoryBanner({ advisory, analyzing }) {
  if (!advisory) return null

  if (!advisory.feasible) {
    return (
      <div className="advisory-card advisory-card--error">
        <div className="advisory-card__icon">
          <IconAlert width={20} height={20} />
        </div>
        <div className="advisory-card__body">
          <div className="row row--between">
            <strong className="advisory-card__title">
              Insufficient Capacity for Selected Batch &amp; Separation Rules — Students Cannot All Be Allocated
            </strong>
            <div className="row" style={{ gap: 6 }}>
              {advisory.additionalSeatsRequired > 0 && (
                <Badge tone="danger">
                  +{advisory.additionalSeatsRequired} seat
                  {advisory.additionalSeatsRequired === 1 ? '' : 's'} needed (~
                  {advisory.additionalBenchesRequired} bench
                  {advisory.additionalBenchesRequired === 1 ? '' : 'es'})
                </Badge>
              )}
              {analyzing && <span className="spinner" />}
            </div>
          </div>
          <p className="advisory-card__msg">{advisory.message}</p>
          {advisory.suggestions?.length > 0 && (
            <ul className="advisory-card__list">
              {advisory.suggestions.map((tip, i) => (
                <li key={i}>{tip}</li>
              ))}
            </ul>
          )}
        </div>
      </div>
    )
  }

  if (advisory.requiresEmptySeats) {
    return (
      <div className="advisory-card advisory-card--warn">
        <div className="advisory-card__icon">
          <IconAlert width={20} height={20} />
        </div>
        <div className="advisory-card__body">
          <div className="row row--between">
            <strong className="advisory-card__title">
              Valid Arrangement · {advisory.emptyBufferSeatsRequired} Vacant Buffer Seat
              {advisory.emptyBufferSeatsRequired === 1 ? '' : 's'} Required Between Same-Batch Students
            </strong>
            <div className="row" style={{ gap: 6 }}>
              <Badge tone="warning">
                Min {advisory.minTotalSeatsRequired} total seats · {advisory.availableSeats} available
              </Badge>
              {analyzing && <span className="spinner" />}
            </div>
          </div>
          <p className="advisory-card__msg">{advisory.message}</p>
          {advisory.suggestions?.length > 0 && (
            <ul className="advisory-card__list">
              {advisory.suggestions.map((tip, i) => (
                <li key={i}>{tip}</li>
              ))}
            </ul>
          )}
        </div>
      </div>
    )
  }

  return (
    <div className="advisory-card advisory-card--ok">
      <div className="advisory-card__icon">
        <IconCheckCircle width={20} height={20} />
      </div>
      <div className="advisory-card__body">
        <div className="row row--between">
          <strong className="advisory-card__title">
            Ready to Generate · All Students Fit Without Mandatory Vacant Buffer Seats
          </strong>
          <div className="row" style={{ gap: 6 }}>
            <Badge tone="success">0 mandatory empty buffer seats</Badge>
            {analyzing && <span className="spinner" />}
          </div>
        </div>
        <p className="advisory-card__msg" style={{ marginBottom: 0 }}>
          {advisory.message}
        </p>
      </div>
    </div>
  )
}
