import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { roomApi, seatingApi, studentApi } from '../api/endpoints'
import { Alert, Badge, Card, Field, Loading } from '../components/ui'

/**
 * Generate a seating plan.
 *
 * The three things an institution decides:
 *
 *  - **which students** — one subject, or everybody;
 *  - **which halls** — all of them, or a selection;
 *  - **the rules** — how far apart conflicting students must sit, and what
 *    makes two students a conflict (the group attributes).
 *
 * The group attributes are the institution's own — "batch", "section",
 * "hostel block" — not a fixed list the backend insists on.
 */

const STRICTNESS = [
  { value: 'BASIC', label: 'Basic', hint: 'Bench mates and horizontal neighbours are kept apart.' },
  {
    value: 'INTERMEDIATE',
    label: 'Intermediate',
    hint: 'Adds row separation — students are staggered between rows.',
  },
  {
    value: 'ADVANCED',
    label: 'Advanced',
    hint: 'Full neighbourhood isolation, in every direction.',
  },
]

const CONFLICT_SCOPES = [
  { value: 'DEPARTMENT', label: 'Same department' },
  { value: 'YEAR', label: 'Same year / session' },
  { value: 'DEPARTMENT_AND_YEAR', label: 'Same department and same year' },
  { value: 'DEPARTMENT_OR_YEAR', label: 'Same department or same year' },
]

const BENCH_PAIRING = [
  { value: 'DIFFERENT_DEPARTMENT_AND_YEAR', label: 'Bench mates differ in department and year' },
  { value: 'DIFFERENT_DEPARTMENT_ONLY', label: 'Bench mates differ in department' },
]

export default function GeneratePage() {
  const navigate = useNavigate()
  const [rooms, setRooms] = useState([])
  const [students, setStudents] = useState([])
  const [identity, setIdentity] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)

  const [form, setForm] = useState({
    strictness: 'BASIC',
    conflictScope: 'DEPARTMENT_AND_YEAR',
    benchPairingRule: 'DIFFERENT_DEPARTMENT_AND_YEAR',
    horizontalDeptIsolation: true,
    groupMatchMode: 'ALL',
    subjectCode: '',
    clearExisting: true,
    roomIds: [],
  })
  const [groupKeys, setGroupKeys] = useState([])

  useEffect(() => {
    let cancelled = false
    async function load() {
      try {
        const [roomPage, studentPage, scheme] = await Promise.all([
          roomApi.list({ page: 0, size: 200, sortBy: 'hallName' }),
          studentApi.list({ page: 0, size: 200 }),
          studentApi.identity().catch(() => null),
        ])
        if (cancelled) return
        setRooms(roomPage?.content || [])
        setStudents(studentPage?.content || [])
        setIdentity(scheme)
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

  /** Every attribute the roster carries, with how many students have it. */
  const availableAttributes = useMemo(() => {
    const counts = new Map()
    students.forEach((student) => {
      const attributes = student.attributes || {}
      Object.entries(attributes).forEach(([key, value]) => {
        if (value === null || value === undefined || value === '') return
        counts.set(key, (counts.get(key) || 0) + 1)
      })
      if (student.department) {
        counts.set('department', (counts.get('department') || 0) + 1)
      }
      if (student.course) {
        counts.set('course', (counts.get('course') || 0) + 1)
      }
      if (student.yearSession) {
        counts.set('yearsession', (counts.get('yearsession') || 0) + 1)
      }
    })
    return [...counts.entries()]
      .map(([key, count]) => ({ key, count }))
      .sort((a, b) => b.count - a.count || a.key.localeCompare(b.key))
  }, [students])

  /** The subjects that are on the roster. */
  const subjects = useMemo(() => {
    const found = new Set()
    students.forEach((student) => {
      if (student.subjectCode) found.add(student.subjectCode)
    })
    return [...found].sort()
  }, [students])

  const totalSeats = rooms
    .filter((room) => form.roomIds.length === 0 || form.roomIds.includes(room.id))
    .reduce(
      (total, room) =>
        total + room.rows * room.columns * (room.benchType === 'DUAL_SEATER' ? 2 : 1),
      0,
    )

  function set(field, value) {
    setForm((current) => ({ ...current, [field]: value }))
  }

  function toggleRoom(id) {
    setForm((current) => ({
      ...current,
      roomIds: current.roomIds.includes(id)
        ? current.roomIds.filter((item) => item !== id)
        : [...current.roomIds, id],
    }))
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
        strictness: form.strictness,
        conflictScope: form.conflictScope,
        benchPairingRule: form.benchPairingRule,
        horizontalDeptIsolation: form.horizontalDeptIsolation,
        groupMatchMode: form.groupMatchMode,
        subjectCode: form.subjectCode.trim() || null,
        roomIds: form.roomIds.length ? form.roomIds : null,
        clearExisting: form.clearExisting,
      }
      if (groupKeys.length) {
        payload.groupAttributes = groupKeys.map((key) => ({ key }))
      }
      const outcome = await seatingApi.arrange(payload)
      setResult(outcome)
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <Loading label="Getting your roster and halls…" />

  return (
    <>
      <header className="topbar">
        <div className="topbar__title">
          <h1>Generate a seating plan</h1>
          <p>{students.length} students on the roster · {rooms.length} halls</p>
        </div>
        <button
          type="button"
          className="btn btn--primary"
          onClick={generate}
          disabled={busy || students.length === 0 || rooms.length === 0}
        >
          {busy && <span className="spinner" />}
          {busy ? 'Solving…' : 'Generate'}
        </button>
      </header>

      <div className="content">
        {error && <Alert tone="error">{error}</Alert>}

        {students.length === 0 && (
          <Alert tone="warning" title="There is nobody to seat yet.">
            Bring a roster in first — <a href="/students">upload an Excel sheet</a> or add
            students by hand.
          </Alert>
        )}
        {rooms.length === 0 && (
          <Alert tone="warning" title="There are no halls yet.">
            Describe at least one hall — <a href="/halls">add a hall</a>.
          </Alert>
        )}

        <div className="grid grid--2">
          <Card title="Who to seat">
            <Field
              label="Subject"
              hint="Leave empty to arrange every student on the roster."
            >
              <select
                value={form.subjectCode}
                onChange={(event) => set('subjectCode', event.target.value)}
              >
                <option value="">All subjects</option>
                {subjects.map((subject) => (
                  <option key={subject} value={subject}>
                    {subject}
                  </option>
                ))}
              </select>
            </Field>

            <Field label="Difficulty" hint="How strictly conflicting students are separated.">
              <div className="stack">
                {STRICTNESS.map((option) => (
                  <label
                    key={option.value}
                    className={`identity-option ${
                      form.strictness === option.value ? 'identity-option--selected' : ''
                    }`}
                  >
                    <input
                      type="radio"
                      name="strictness"
                      checked={form.strictness === option.value}
                      onChange={() => set('strictness', option.value)}
                    />
                    <span className="spread">
                      <strong>{option.label}</strong>
                      <span className="small muted">{option.hint}</span>
                    </span>
                  </label>
                ))}
              </div>
            </Field>

            <Field label="Who counts as a conflict">
              <select
                value={form.conflictScope}
                onChange={(event) => set('conflictScope', event.target.value)}
              >
                {CONFLICT_SCOPES.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </Field>

            <Field label="Bench mates">
              <select
                value={form.benchPairingRule}
                onChange={(event) => set('benchPairingRule', event.target.value)}
              >
                {BENCH_PAIRING.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </Field>

            <label className="checkbox" style={{ marginBottom: 12 }}>
              <input
                type="checkbox"
                checked={form.horizontalDeptIsolation}
                onChange={(event) => set('horizontalDeptIsolation', event.target.checked)}
              />
              Keep departments apart across the row as well
            </label>

            <label className="checkbox">
              <input
                type="checkbox"
                checked={form.clearExisting}
                onChange={(event) => set('clearExisting', event.target.checked)}
              />
              Replace the current plan
            </label>
          </Card>

          <Card
            title="Groups to keep apart"
            subtitle="The attributes your own roster carries."
          >
            {availableAttributes.length === 0 ? (
              <p className="small muted">
                No attributes were found on the roster. Students can still be separated by
                department, course and year — see “Who counts as a conflict”.
              </p>
            ) : (
              <>
                <p className="small muted">
                  Pick the attributes that make two students a conflict — a batch, a
                  section, a hostel block, whatever your institution uses.
                </p>
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
                        <strong>{attribute.key}</strong>
                        <span className="small muted">
                          {attribute.count} student{attribute.count === 1 ? '' : 's'} carry it
                        </span>
                      </span>
                    </label>
                  ))}
                </div>
                {groupKeys.length > 0 && (
                  <Field label="Two students conflict when they share…">
                    <select
                      value={form.groupMatchMode}
                      onChange={(event) => set('groupMatchMode', event.target.value)}
                    >
                      <option value="ALL">every one of these attributes</option>
                      <option value="ANY">at least one of these attributes</option>
                    </select>
                  </Field>
                )}
              </>
            )}
          </Card>
        </div>

        <Card
          title="Halls to use"
          actions={
            <div className="btn-row">
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => set('roomIds', [])}
                disabled={form.roomIds.length === 0}
              >
                All halls
              </button>
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => set('roomIds', rooms.map((room) => room.id))}
              >
                Select all
              </button>
            </div>
          }
        >
          <p className="small muted">
            {totalSeats} seat{totalSeats === 1 ? '' : 's'} available
            {form.roomIds.length > 0 && ` in the selected halls`}.
          </p>
          <div className="grid grid--3">
            {rooms.map((room) => (
              <label
                key={room.id}
                className={`column ${form.roomIds.includes(room.id) ? 'column--selected' : ''}`}
              >
                <div className="column__title">
                  <span>{room.hallName}</span>
                  <input
                    type="checkbox"
                    style={{ width: 'auto' }}
                    checked={form.roomIds.includes(room.id)}
                    onChange={() => toggleRoom(room.id)}
                  />
                </div>
                <div className="small muted">
                  {room.rows} × {room.columns} ·{' '}
                  {room.rows * room.columns * (room.benchType === 'DUAL_SEATER' ? 2 : 1)} seats
                </div>
              </label>
            ))}
          </div>
        </Card>

        {result && (
          <Card title="The result">
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
                <div className="stat__label">Seats</div>
                <div className="stat__value">{result.totalSlots}</div>
              </div>
              <div className="stat">
                <div className="stat__label">Solved</div>
                <div className="stat__value" style={{ fontSize: 18, marginTop: 10 }}>
                  {result.solved ? <Badge tone="success">completely</Badge> : <Badge tone="warning">with gaps</Badge>}
                </div>
              </div>
            </div>

            {result.unplaced > 0 && (
              <Alert tone="warning" title="Some students could not be placed.">
                Either not enough seats, or the rules are too strict for the halls that
                were selected. Try a lower difficulty, fewer group attributes, or more
                halls.
              </Alert>
            )}

            <div className="btn-row">
              <button
                type="button"
                className="btn btn--primary"
                onClick={() => navigate('/plan')}
              >
                Open the seating plan
              </button>
              <button type="button" className="btn" onClick={() => navigate('/exports')}>
                Export it
              </button>
            </div>
          </Card>
        )}
      </div>
    </>
  )
}
