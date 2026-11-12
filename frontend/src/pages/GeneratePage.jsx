import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { roomApi, seatingApi, studentApi } from '../api/endpoints'
import { Alert, Badge, Card, Field, Loading } from '../components/ui'

const STRICTNESS = [
  {
    value: 'NORMAL',
    label: 'Normal',
    hint: 'Separates bench mates and horizontal neighbours in the same row, with clean front-to-back column continuity.',
  },
  {
    value: 'INTERMEDIATE',
    label: 'Intermediate',
    hint: 'Separates classmates horizontally and vertically (front-to-back staggered rows).',
  },
  {
    value: 'HARD',
    label: 'Hard (Rigid)',
    hint: 'Full 8-directional separation — horizontal, vertical, and diagonal neighbours.',
  },
]

function readAttr(student, key) {
  const norm = (key || '').toLowerCase().replace(/[^a-z0-9]/g, '')
  return student?.attributes?.[norm] || ''
}

export default function GeneratePage() {
  const navigate = useNavigate()
  const [rooms, setRooms] = useState([])
  const [students, setStudents] = useState([])
  const [totalStudentCount, setTotalStudentCount] = useState(0)
  const [identity, setIdentity] = useState(null)
  const [rosterSchema, setRosterSchema] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)

  const [strictness, setStrictness] = useState('NORMAL')
  const [groupKeys, setGroupKeys] = useState([])
  const [groupMatchMode, setGroupMatchMode] = useState('ALL')
  const [scopeAttribute, setScopeAttribute] = useState('')
  const [scopeValue, setScopeValue] = useState('')
  const [clearExisting, setClearExisting] = useState(true)
  const [roomIds, setRoomIds] = useState([])

  useEffect(() => {
    let cancelled = false
    async function load() {
      try {
        const [roomPage, studentPage, scheme, schema] = await Promise.all([
          roomApi.list({ page: 0, size: 200, sortBy: 'hallName' }),
          studentApi.list({ page: 0, size: 1000 }),
          studentApi.identity().catch(() => null),
          studentApi.schema().catch(() => null),
        ])
        if (cancelled) return
        setRooms(roomPage?.content || [])
        setStudents(studentPage?.content || [])
        setTotalStudentCount(studentPage?.totalElements ?? (studentPage?.content || []).length)
        setIdentity(scheme)
        setRosterSchema(schema)

        const defaultGroups = scheme?.classmateAttributes?.length
          ? scheme.classmateAttributes
          : schema?.suggestedClassmateGroups?.length
          ? schema.suggestedClassmateGroups[0].attributes
          : []
        if (defaultGroups.length) {
          setGroupKeys(defaultGroups)
        }
        if (scheme?.scopeAttribute) {
          setScopeAttribute(scheme.scopeAttribute)
        }
      } catch (failure) {
        if (!cancelled) setError(failure.message)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    load()
    return () => {
      cancelled = true
    }
  }, [])

  const titleByKey = useMemo(() => {
    const map = new Map()
    ;(rosterSchema?.columns || []).forEach((col) => {
      map.set(col.key, col.title || col.key)
    })
    return map
  }, [rosterSchema])

  const availableAttributes = useMemo(
    () => (rosterSchema?.columns || []).filter((col) => col.key !== 'name'),
    [rosterSchema],
  )

  const scopeValueOptions = useMemo(() => {
    if (!scopeAttribute) return []
    const fromSchema = rosterSchema?.distinctValuesByAttribute?.[scopeAttribute]
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
    }
  }, [groupKeys, inScopeStudents])

  const totalSeats = rooms
    .filter((room) => roomIds.length === 0 || roomIds.includes(room.id))
    .reduce(
      (total, room) =>
        total + room.rows * room.columns * (room.benchType === 'DUAL_SEATER' ? 2 : 1),
      0,
    )

  function toggleRoom(id) {
    setRoomIds((current) =>
      current.includes(id) ? current.filter((item) => item !== id) : [...current, id],
    )
  }

  function toggleGroup(key) {
    setGroupKeys((current) =>
      current.includes(key) ? current.filter((item) => item !== key) : [...current, key],
    )
  }

  async function generate() {
    setError(null)
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
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <Loading label="Loading roster schema and halls…" />

  const groupLabels = groupKeys.map((k) => titleByKey.get(k) || k)

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Generate a seating plan</h1>
          <p>
            {totalStudentCount} student{totalStudentCount === 1 ? '' : 's'} on the roster ·{' '}
            {rooms.length} hall{rooms.length === 1 ? '' : 's'} ({totalSeats} seats selected)
          </p>
        </div>
        <button
          type="button"
          className="btn btn--primary"
          onClick={generate}
          disabled={busy || totalStudentCount === 0 || rooms.length === 0}
        >
          {busy && <span className="spinner" />}
          {busy ? 'Solving…' : 'Generate Seating Plan'}
        </button>
      </div>

      {error && <Alert tone="error">{error}</Alert>}

      {totalStudentCount === 0 && (
        <Alert tone="warning" title="No students on the roster yet.">
          <a href="/students">Upload an Excel sheet</a> or add students first.
        </Alert>
      )}
      {rooms.length === 0 && (
        <Alert tone="warning" title="No exam halls configured yet.">
          <a href="/halls">Add at least one hall</a> before generating a plan.
        </Alert>
      )}

      <div className="grid grid--2">
        <Card title="1. Separation Difficulty & Round Scope">
          <Field label="Separation Difficulty" hint="Controls horizontal, vertical, and diagonal gaps.">
            <div className="stack">
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
                  <span className="spread">
                    <strong>{option.label}</strong>
                    <span className="small muted">{option.hint}</span>
                  </span>
                </label>
              ))}
            </div>
          </Field>

          <div className="grid grid--2">
            <Field
              label="Exam Round Field (optional)"
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
            <div
              style={{
                marginBottom: 14,
                padding: '10px 14px',
                background: 'var(--bg-muted, #f4f4f5)',
                borderRadius: 6,
              }}
            >
              <div style={{ fontWeight: 600, marginBottom: 4 }}>Active Cohort Rule:</div>
              <div className="small">
                Students sharing{' '}
                <strong>
                  {groupMatchMode === 'ANY' ? 'any of ' : ''}
                  {groupLabels.join(' + ')}
                </strong>{' '}
                will be separated under <strong>{strictness}</strong> geometry.
              </div>
              {cohortSummary && (
                <div className="faint small" style={{ marginTop: 4 }}>
                  {inScopeStudents.length} students in scope · {cohortSummary.cohortCount} cohorts ·
                  largest: {cohortSummary.largestLabel} ({cohortSummary.largestCount})
                </div>
              )}
            </div>
          )}

          <label className="checkbox">
            <input
              type="checkbox"
              checked={clearExisting}
              onChange={(event) => setClearExisting(event.target.checked)}
            />
            {scopeValue
              ? `Replace existing allocations for '${scopeValue}' (preserve other rounds)`
              : 'Replace the current seating plan'}
          </label>
        </Card>

        <Card
          title="2. Classmate / Cohort Attributes"
          subtitle="Select one or more dynamic attributes from your roster that define classmates."
        >
          {identity?.classmateAttributes?.length > 0 && (
            <div style={{ marginBottom: 12 }} className="row row--between">
              <div>
                <span className="small muted">Saved institution default: </span>
                <Badge tone="info">
                  {identity.classmateAttributes.map((k) => titleByKey.get(k) || k).join(' + ')}
                </Badge>
              </div>
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => setGroupKeys(identity.classmateAttributes)}
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
              <div className="btn-row" style={{ flexWrap: 'wrap' }}>
                {rosterSchema.suggestedClassmateGroups.map((preset) => {
                  const active =
                    preset.attributes.length === groupKeys.length &&
                    preset.attributes.every((k) => groupKeys.includes(k))
                  return (
                    <button
                      key={preset.attributes.join('+')}
                      type="button"
                      className={`btn btn--sm ${active ? 'btn--primary' : ''}`}
                      onClick={() => setGroupKeys(preset.attributes)}
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
              <div className="stack">
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
                    <span className="spread">
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
        title="3. Exam Halls"
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
        <div className="grid grid--3">
          {rooms.map((room) => (
            <label
              key={room.id}
              className={`column ${roomIds.includes(room.id) ? 'column--selected' : ''}`}
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
                {room.rows} × {room.columns} ({room.benchType === 'DUAL_SEATER' ? 'Dual' : 'Single'}) ·{' '}
                {room.rows * room.columns * (room.benchType === 'DUAL_SEATER' ? 2 : 1)} seats
              </div>
            </label>
          ))}
        </div>
      </Card>

      {result && (
        <Card title="Generation Summary">
          <div className="grid grid--4">
            <div className="stat">
              <div className="stat__label">Seated</div>
              <div className="stat__value">{result.seated}</div>
            </div>
            <div className="stat">
              <div className="stat__label">Unplaced</div>
              <div className="stat__value">{result.unplaced}</div>
            </div>
            <div className="stat">
              <div className="stat__label">Hall Capacity</div>
              <div className="stat__value">{result.totalSlots}</div>
            </div>
            <div className="stat">
              <div className="stat__label">Status</div>
              <div className="stat__value" style={{ fontSize: 18, marginTop: 10 }}>
                {result.solved ? (
                  <Badge tone="success">100% Solved</Badge>
                ) : (
                  <Badge tone="warning">Partial</Badge>
                )}
              </div>
            </div>
          </div>

          <div className="btn-row" style={{ marginTop: 14 }}>
            <button type="button" className="btn btn--primary" onClick={() => navigate('/plan')}>
              Inspect Seating Plan
            </button>
            <button type="button" className="btn" onClick={() => navigate('/exports')}>
              Export Excel &amp; PDF
            </button>
          </div>
        </Card>
      )}
    </>
  )
}
