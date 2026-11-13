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
import { IconAlert, IconCheckCircle, IconInfo, IconWand } from '../components/Icons'

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
  const [roomIds, setRoomIds] = useState([])

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

        if (defaultGroups.length) {
          setGroupKeys(defaultGroups.map((k) => normalizeKey(k)).filter(Boolean))
        } else if (loadedStudents.length > 0) {
          // Fallback auto-detection directly from student attributes if schema had no suggestions
          const sampleKeys = Object.keys(loadedStudents[0]?.attributes || {})
            .map((k) => normalizeKey(k))
            .filter((k) => k && k !== 'name' && !k.includes('roll') && !k.endsWith('id'))
          if (sampleKeys.length > 0) {
            setGroupKeys([sampleKeys[0]])
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

  // Run live pre-flight feasibility & empty-seat check whenever parameters change
  useEffect(() => {
    if (loading || totalStudentCount === 0 || rooms.length === 0) {
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
          roomIds: roomIds.length ? roomIds : null,
          clearExisting,
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
    totalStudentCount,
    rooms.length,
    strictness,
    groupKeys,
    groupMatchMode,
    scopeAttribute,
    scopeValue,
    clearExisting,
    roomIds,
  ])

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

  const inScopeStudents = useMemo(() => {
    if (!scopeAttribute || !scopeValue) return students
    const target = scopeValue.trim().toLowerCase()
    return students.filter((s) => readAttr(s, scopeAttribute).trim().toLowerCase() === target)
  }, [students, scopeAttribute, scopeValue])

  const inScopeCount =
    !scopeAttribute || !scopeValue
      ? Math.max(inScopeStudents.length, totalStudentCount)
      : inScopeStudents.length

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
      batches: sorted.slice(0, 6).map(([label, count]) => ({ label, count })),
    }
  }, [groupKeys, inScopeStudents])

  const activeRooms = useMemo(
    () => rooms.filter((room) => roomIds.length === 0 || roomIds.includes(room.id)),
    [rooms, roomIds],
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

  const planSeatLimit = usage?.seatsPerGeneration ?? null
  const planGenUsed = usage?.generationsUsed ?? null
  const planGenLimit = usage?.generationsLimit ?? null
  const exceedsPlanSeats = planSeatLimit !== null && inScopeCount > planSeatLimit
  const exceedsHallSeats = rooms.length > 0 && inScopeCount > totalSeats
  const exceedsMonthlyGens =
    planGenLimit !== null && planGenUsed !== null && planGenUsed >= planGenLimit

  function toggleRoom(id) {
    setRoomIds((current) =>
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
        roomIds: roomIds.length ? roomIds : null,
        clearExisting,
      }
      if (groupKeys.length) {
        payload.groupAttributes = groupKeys
      }
      const outcome = await seatingApi.arrange(payload)
      setResult(outcome)
      if (outcome?.advisory) {
        setAdvisory(outcome.advisory)
      }
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

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Generate Seating Arrangement</h1>
          <p>
            <strong>{totalStudentCount.toLocaleString()}</strong> student
            {totalStudentCount === 1 ? '' : 's'} uploaded ·{' '}
            <strong>{activeRooms.length}</strong> of {rooms.length} hall
            {rooms.length === 1 ? '' : 's'} active (
            <strong>{totalSeats.toLocaleString()}</strong> seats across{' '}
            {totalBenches.toLocaleString()} benches)
          </p>
        </div>
        <button
          type="button"
          className="btn btn--primary btn--lg"
          onClick={generate}
          disabled={busy || totalStudentCount === 0 || rooms.length === 0}
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

        {/* Prominent Windows-Visible Seating Capacity & Batch Allocation Limits Panel */}
        {(totalStudentCount > 0 || rooms.length > 0) && (
          <div
            className={
              'capacity-overview' +
              (exceedsHallSeats || exceedsPlanSeats || exceedsMonthlyGens || (advisory && !advisory.feasible)
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
                    Review your physical hall capacity, subscription seat ceiling, and per-batch
                    anti-copying buffer requirements before generating.
                  </p>
                </div>
              </div>
              <div className="row" style={{ gap: 8 }}>
                {totalStudentCount > 0 && rooms.length > 0 && (
                  <Badge
                    tone={
                      exceedsHallSeats || exceedsPlanSeats || (advisory && !advisory.feasible)
                        ? 'danger'
                        : advisory?.requiresEmptySeats
                        ? 'warning'
                        : 'success'
                    }
                  >
                    {exceedsHallSeats
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
                label="Students to Seat"
                value={inScopeCount.toLocaleString()}
                limit={totalSeats > 0 ? totalSeats : undefined}
                unit=" seats"
                accent={exceedsHallSeats ? 'amber' : 'brand'}
                hint={
                  scopeValue
                    ? `Filtered by ${titleByKey.get(scopeAttribute) || scopeAttribute}: ${scopeValue}`
                    : `${availableAttributes.length} roster attribute${availableAttributes.length === 1 ? '' : 's'} detected`
                }
              />
              <Stat
                label="Selected Hall Capacity"
                value={totalSeats.toLocaleString()}
                accent={exceedsHallSeats ? 'amber' : 'emerald'}
                hint={`${activeRooms.length} hall${activeRooms.length === 1 ? '' : 's'} · ${totalBenches.toLocaleString()} benches`}
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
                label="Largest Student Batch"
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
                    : 'Select cohort attributes below'
                }
              />
            </div>

            <div className="capacity-rules-callout">
              <div className="capacity-rules-callout__item">
                <strong>Maximum Seating Capacity Limit:</strong> Each generation can allocate up to{' '}
                <strong>{totalSeats.toLocaleString()} physical hall seats</strong>
                {planSeatLimit !== null && (
                  <>
                    {' '}
                    (and up to <strong>{planSeatLimit.toLocaleString()} seats/generation</strong> on
                    your current subscription plan)
                  </>
                )}
                .{' '}
                {exceedsHallSeats && (
                  <span className="capacity-rules-callout__alert">
                    Currently {inScopeCount - totalSeats} student(s) exceed available hall seats —{' '}
                    <Link to="/halls">add or enable more halls</Link>.
                  </span>
                )}
                {exceedsPlanSeats && (
                  <span className="capacity-rules-callout__alert">
                    Currently {inScopeCount - planSeatLimit} student(s) exceed your plan&apos;s
                    per-generation seat cap —{' '}
                    <Link to="/settings">request a plan or limit upgrade</Link>.
                  </span>
                )}
              </div>
              <div className="capacity-rules-callout__item">
                <strong>Batch Size Overflow &amp; Vacant Buffer Seats:</strong> Under{' '}
                <strong>{selectedStrictnessObj.label}</strong> separation, classmates from the same
                batch cannot occupy forbidden adjacent seats. If a single batch exceeds the
                available conflict-free positions
                {maxBatchSeats !== null ? ` (max ${maxBatchSeats} seats for one batch in selected halls)` : ''}
                , <strong>excess students will remain unallocated (requiring additional halls/benches)</strong>,
                and when one batch outnumbers other batches,{' '}
                <strong>benches will intentionally leave vacant buffer seats</strong> to prevent
                same-batch neighbours.
              </div>
            </div>

            {cohortSummary?.batches?.length > 0 && (
              <div className="cohort-strip">
                <span className="cohort-strip__label">Detected Batch Sizes:</span>
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
                        {maxBatchSeats !== null && (
                          <small>/ {maxBatchSeats}</small>
                        )}
                      </span>
                    )
                  })}
                </div>
              </div>
            )}
          </div>
        )}

        {/* Live Pre-Flight Feasibility & Mandatory Empty-Seat Diagnostic Banner */}
        {advisory && totalStudentCount > 0 && rooms.length > 0 && (
          <AdvisoryBanner advisory={advisory} analyzing={analyzing} />
        )}

        <div className="grid grid--2">
          <Card
            title="1. Separation Difficulty & Round Scope"
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
                    empty buffer seats rather than violating separation rules — and tells you how
                    many extra seats/benches are needed if capacity is too tight.
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

            <div className="grid grid--2">
              <Field
                label="Exam Round Field (Optional)"
                hint="Filter by Exam Date, Paper, Shift, etc."
              >
                <select
                  value={scopeAttribute}
                  onChange={(e) => {
                    setScopeAttribute(e.target.value)
                    setScopeValue('')
                  }}
                >
                  <option value="">Entire roster</option>
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

            {groupKeys.length > 0 && (
              <div className="rule-callout">
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
                    {inScopeCount} students in scope · {cohortSummary.cohortCount} groups · largest:{' '}
                    {cohortSummary.largestLabel} ({cohortSummary.largestCount})
                  </div>
                )}
              </div>
            )}

            <label className="check" style={{ marginTop: 10 }}>
              <input
                type="checkbox"
                checked={clearExisting}
                onChange={(event) => setClearExisting(event.target.checked)}
              />
              <span>
                {scopeValue
                  ? `Replace existing allocations for '${scopeValue}' (preserve other rounds)`
                  : 'Replace the current seating plan'}
              </span>
            </label>
          </Card>

          <Card
            title="2. Classmate / Cohort Attributes"
            subtitle="Select one or more dynamic attributes from your roster that define classmates."
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

        <Card
          title="3. Examination Halls"
          subtitle={
            roomIds.length === 0
              ? `Using all ${rooms.length} configured hall(s) (${totalSeats.toLocaleString()} seats).`
              : `Using ${roomIds.length} selected hall(s) (${totalSeats.toLocaleString()} seats).`
          }
          actions={
            <div className="btn-row">
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => setRoomIds([])}
                disabled={roomIds.length === 0}
              >
                Use all halls
              </button>
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => setRoomIds(rooms.map((room) => room.id))}
              >
                Select all
              </button>
            </div>
          }
        >
          {rooms.length === 0 ? (
            <p className="small muted">No halls configured yet.</p>
          ) : (
            <div className="grid grid--3">
              {rooms.map((room) => {
                const cap =
                  room.rows * room.columns * (room.benchType === 'DUAL_SEATER' ? 2 : 1)
                const selected = roomIds.length === 0 || roomIds.includes(room.id)
                return (
                  <label
                    key={room.id}
                    className={`column ${selected ? 'column--selected' : ''}`}
                  >
                    <div className="column__title">
                      <span>{room.hallName}</span>
                      <input
                        type="checkbox"
                        style={{ width: 'auto' }}
                        checked={roomIds.includes(room.id)}
                        onChange={() => toggleRoom(room.id)}
                      />
                    </div>
                    <div className="small muted">
                      {room.rows} × {room.columns} (
                      {room.benchType === 'DUAL_SEATER' ? 'Dual bench' : 'Single seat'}) ·{' '}
                      <strong>{cap}</strong> seats
                    </div>
                  </label>
                )
              })}
            </div>
          )}
        </Card>

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
