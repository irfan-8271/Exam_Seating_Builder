import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { studentApi } from '../api/endpoints'
import {
  Alert,
  AlertList,
  Badge,
  Card,
  EmptyState,
  Field,
  Loading,
  Modal,
  Table,
} from '../components/ui'

/**
 * The students of this institution.
 *
 * Two ways in: one at a time, or a whole roster from Excel. The Excel route
 * is a conversation — the system analyses the sheet, shows what it found and
 * asks which attribute (or which pair of attributes) identifies a student,
 * because no two institutions number their students the same way.
 */
export default function StudentsPage() {
  const [page, setPage] = useState(0)
  const [size] = useState(25)
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const [scheme, setScheme] = useState(null)
  const [showAdd, setShowAdd] = useState(false)
  const [showImport, setShowImport] = useState(false)
  const [editing, setEditing] = useState(null)
  const [showIdentity, setShowIdentity] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [students, identity] = await Promise.all([
        studentApi.list({ page, size }),
        studentApi.identity().catch(() => null),
      ])
      setData(students)
      setScheme(identity)
    } catch (failure) {
      setError(failure.message)
    } finally {
      setLoading(false)
    }
  }, [page, size])

  useEffect(() => {
    load()
  }, [load])

  async function clearIdentity() {
    try {
      await studentApi.clearIdentity()
      setNotice('Students are identified automatically again.')
      load()
    } catch (failure) {
      setError(failure.message)
    }
  }

  async function remove(student) {
    if (!window.confirm(`Remove ${student.name} from the roster?`)) return
    try {
      await studentApi.remove(student.id)
      setNotice(`Removed ${student.name}.`)
      load()
    } catch (failure) {
      setError(failure.message)
    }
  }

  const rows = data?.content || []

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Students</h1>
          <p>
            {data ? `${data.totalElements} student${data.totalElements === 1 ? '' : 's'}` : 'Loading…'}
            {scheme?.attributes?.length
              ? ` · identified by ${scheme.attributes.join(' + ')}`
              : ' · identification is worked out from each sheet'}
          </p>
        </div>
        <div className="btn-row">
          <button type="button" className="btn" onClick={() => setShowImport(true)}>
            Upload a roster
          </button>
          <button type="button" className="btn btn--primary" onClick={() => setShowAdd(true)}>
            Add a student
          </button>
        </div>
      </div>

        {error && <Alert tone="error">{error}</Alert>}
        {notice && <Alert tone="success">{notice}</Alert>}

        {scheme?.attributes?.length ? (
          <Alert tone="info" title="Institution Configuration">
            <div>
              <strong>Student Identification:</strong> {scheme.attributes.join(' + ')}
              {scheme.label ? ` (${scheme.label})` : ''}
            </div>
            {scheme.classmateAttributes?.length ? (
              <div style={{ marginTop: 4 }}>
                <strong>Classmates / Academic Group:</strong> {scheme.classmateAttributes.join(' + ')}
              </div>
            ) : null}
            <div className="btn-row" style={{ marginTop: 8 }}>
              <button type="button" className="btn btn--sm" onClick={() => setShowIdentity(true)}>
                Configure identification & grouping
              </button>
              <button type="button" className="btn btn--sm" onClick={clearIdentity}>
                Go back to automatic detection
              </button>
            </div>
          </Alert>
        ) : null}

        <Card flush>
          {loading ? (
            <Loading label="Loading students…" />
          ) : rows.length === 0 ? (
            <EmptyState title="No students yet">
              Upload an Excel roster, or add the first student by hand.
            </EmptyState>
          ) : (
            <>
              <Table
                rows={rows}
                onRowClick={(row) => setEditing(row)}
                columns={[
                  {
                    key: 'identity',
                    title: 'Identifier',
                    render: (row) => (
                      <span>
                        <strong>{row.identityKey || row.rollNo || '—'}</strong>
                        {row.rollNo && row.identityKey && row.rollNo !== row.identityKey && (
                          <div className="faint small">roll no. {row.rollNo}</div>
                        )}
                      </span>
                    ),
                  },
                  { key: 'name', title: 'Name' },
                  {
                    key: 'department',
                    title: 'Department',
                    render: (row) => row.department || <span className="faint">—</span>,
                  },
                  {
                    key: 'yearSession',
                    title: 'Year / session',
                    render: (row) => row.yearSession || <span className="faint">—</span>,
                  },
                  {
                    key: 'subjectCode',
                    title: 'Subject',
                    render: (row) => row.subjectCode || <span className="faint">—</span>,
                  },
                  {
                    key: 'actions',
                    title: '',
                    render: (row) => (
                      <div className="btn-row" onClick={(event) => event.stopPropagation()}>
                        <button type="button" className="btn btn--sm" onClick={() => setEditing(row)}>
                          Edit
                        </button>
                        <button
                          type="button"
                          className="btn btn--sm btn--danger"
                          onClick={() => remove(row)}
                        >
                          Remove
                        </button>
                      </div>
                    ),
                  },
                ]}
              />
              {data.totalPages > 1 && (
                <div className="row row--between" style={{ padding: 12 }}>
                  <span className="small muted">
                    Page {data.page + 1} of {data.totalPages}
                  </span>
                  <div className="btn-row">
                    <button
                      type="button"
                      className="btn btn--sm"
                      disabled={data.first}
                      onClick={() => setPage((p) => Math.max(0, p - 1))}
                    >
                      ← Previous
                    </button>
                    <button
                      type="button"
                      className="btn btn--sm"
                      disabled={data.last}
                      onClick={() => setPage((p) => p + 1)}
                    >
                      Next →
                    </button>
                  </div>
                </div>
              )}
            </>
          )}
        </Card>
       {showAdd && (
        <StudentDialog
          title="Add a student"
          onClose={() => setShowAdd(false)}
          onSaved={() => {
            setShowAdd(false)
            setNotice('The student was added.')
            load()
          }}
        />
      )}

      {editing && (
        <StudentDialog
          title={`Edit ${editing.name}`}
          student={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            setNotice('The student was updated.')
            load()
          }}
        />
      )}

      {showImport && (
        <ImportDialog
          onClose={() => setShowImport(false)}
          onImported={(message) => {
            setShowImport(false)
            setNotice(message)
            load()
          }}
        />
      )}

      {showIdentity && (
        <IdentityDialog
          scheme={scheme}
          onClose={() => setShowIdentity(false)}
          onSaved={() => {
            setShowIdentity(false)
            setNotice('The identification rule was changed.')
            load()
          }}
        />
      )}
    </>
  )
}

/** The add / edit form of a single student. */
function StudentDialog({ title, student, onClose, onSaved }) {
  const editing = Boolean(student)
  const [form, setForm] = useState({
    name: student?.name || '',
    rollNo: student?.rollNo || '',
    department: student?.department || '',
    course: student?.course || '',
    yearSession: student?.yearSession || '',
    subjectCode: student?.subjectCode || '',
  })
  const [extra, setExtra] = useState(() => {
    const attributes = student?.attributes || {}
    const known = ['department', 'course', 'yearsession', 'subjectcode']
    return Object.entries(attributes).filter(([key]) => !known.includes(key))
  })
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  function set(field, value) {
    setForm((current) => ({ ...current, [field]: value }))
  }

  async function submit(event) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      const attributes = {}
      extra.forEach(([key, value]) => {
        if (key && value) attributes[key.trim().toLowerCase()] = value
      })
      const payload = {
        name: form.name.trim(),
        rollNo: form.rollNo.trim() || null,
        department: form.department.trim() || null,
        course: form.course.trim() || null,
        yearSession: form.yearSession.trim() || null,
        subjectCode: form.subjectCode.trim() || null,
        attributes,
      }
      if (editing) {
        await studentApi.patch(student.id, payload)
      } else {
        await studentApi.create(payload)
      }
      onSaved()
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title={title}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" form="student-form" className="btn btn--primary" disabled={busy}>
            {busy && <span className="spinner" />}
            {editing ? 'Save changes' : 'Add the student'}
          </button>
        </>
      }
    >
      {error && <Alert tone="error">{error}</Alert>}
      <form id="student-form" onSubmit={submit}>
        <Field label="Name">
          <input
            type="text"
            value={form.name}
            onChange={(event) => set('name', event.target.value)}
            required
            autoFocus
          />
        </Field>
        <Field
          label="Roll number / identifier"
          hint="Optional — a roster may identify its students by something else entirely."
        >
          <input
            type="text"
            value={form.rollNo}
            onChange={(event) => set('rollNo', event.target.value)}
          />
        </Field>
        <div className="grid grid--2">
          <Field label="Department">
            <input
              type="text"
              value={form.department}
              onChange={(event) => set('department', event.target.value)}
            />
          </Field>
          <Field label="Course / programme">
            <input
              type="text"
              value={form.course}
              onChange={(event) => set('course', event.target.value)}
            />
          </Field>
          <Field label="Year / session">
            <input
              type="text"
              value={form.yearSession}
              onChange={(event) => set('yearSession', event.target.value)}
            />
          </Field>
          <Field label="Subject code">
            <input
              type="text"
              value={form.subjectCode}
              onChange={(event) => set('subjectCode', event.target.value)}
            />
          </Field>
        </div>

        <fieldset>
          <legend>Other attributes</legend>
          <p className="small muted">
            Anything your institution groups students by — batch, section, hostel,
            centre. These become the group attributes you can separate students by
            when generating.
          </p>
          {extra.map(([key, value], index) => (
            <div className="row" key={index} style={{ marginBottom: 8 }}>
              <input
                type="text"
                placeholder="attribute, e.g. batch"
                value={key}
                onChange={(event) => {
                  const next = [...extra]
                  next[index] = [event.target.value, value]
                  setExtra(next)
                }}
              />
              <input
                type="text"
                placeholder="value"
                value={value}
                onChange={(event) => {
                  const next = [...extra]
                  next[index] = [key, event.target.value]
                  setExtra(next)
                }}
              />
              <button
                type="button"
                className="btn btn--sm btn--danger"
                onClick={() => setExtra(extra.filter((_, i) => i !== index))}
              >
                Remove
              </button>
            </div>
          ))}
          <button
            type="button"
            className="btn btn--sm"
            onClick={() => setExtra([...extra, ['', '']])}
          >
            Add an attribute
          </button>
        </fieldset>
      </form>
    </Modal>
  )
}

/**
 * The roster upload — analyse, configure identification and grouping, then import.
 *
 * Step 1: Upload and analyse the sheet.
 * Step 2: Configure student identification (single or composite unique attributes).
 * Step 3: Configure classmate grouping (single or composite cohort attributes).
 * Step 4: Import and persist the institutional scheme.
 */
function ImportDialog({ onClose, onImported }) {
  const fileRef = useRef(null)
  const [file, setFile] = useState(null)
  const [preview, setPreview] = useState(null)
  const [chosen, setChosen] = useState([])
  const [chosenClassmates, setChosenClassmates] = useState([])
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)

  async function analyse(event) {
    event?.preventDefault()
    if (!file) return
    setError(null)
    setBusy(true)
    try {
      const data = await studentApi.preview(file)
      setPreview(data)
      const suggested = data.suggestedIdentities || []
      const first = suggested.find((item) => item.unique) || suggested[0]
      setChosen(first ? first.attributes : [])

      const suggestedGroups = data.suggestedClassmateGroups || []
      const firstGroup = suggestedGroups[0]
      setChosenClassmates(
        firstGroup
          ? firstGroup.attributes
          : data.currentClassmateAttributes && data.currentClassmateAttributes.length
          ? data.currentClassmateAttributes
          : []
      )
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  function toggle(key) {
    setChosen((current) =>
      current.includes(key) ? current.filter((item) => item !== key) : [...current, key],
    )
  }

  function toggleClassmate(key) {
    setChosenClassmates((current) =>
      current.includes(key) ? current.filter((item) => item !== key) : [...current, key],
    )
  }

  async function runImport() {
    setError(null)
    setBusy(true)
    try {
      const outcome = await studentApi.import(file, chosen, chosenClassmates)
      setResult(outcome)
      if (!outcome.errors || outcome.errors.length === 0) {
        await rememberChoice(chosen, chosenClassmates, preview)
        onImported(`Imported ${outcome.imported} of ${outcome.totalRows} students.`)
      }
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  /** Saves the chosen attributes as the institution's identification & classmate rule. */
  async function rememberChoice(attributes, classmateAttributes, analysis) {
    if (!attributes || attributes.length === 0) return
    const label = (analysis?.suggestedIdentities || []).find(
      (item) =>
        item.attributes.length === attributes.length &&
        item.attributes.every((entry) => attributes.includes(entry)),
    )?.label
    try {
      await studentApi.saveIdentity({
        label: label || attributes.join(' + '),
        attributes,
        classmateAttributes: classmateAttributes && classmateAttributes.length ? classmateAttributes : undefined,
      })
    } catch {
      // Endpoint may need ADMIN; import itself succeeded.
    }
  }

  const columns = preview?.columns || []
  const attributes = columns.filter((column) => column.kind === 'attribute')

  return (
    <Modal
      title="Upload a roster"
      wide
      onClose={onClose}
      footer={
        result ? (
          <button type="button" className="btn btn--primary" onClick={onClose}>
            Done
          </button>
        ) : (
          <>
            <button type="button" className="btn" onClick={onClose}>
              Cancel
            </button>
            {preview && (
              <button
                type="button"
                className="btn btn--primary"
                disabled={busy || chosen.length === 0}
                onClick={runImport}
              >
                {busy && <span className="spinner" />}
                Import {preview.totalRows} rows
              </button>
            )}
          </>
        )
      }
    >
      {error && <Alert tone="error">{error}</Alert>}

      {result ? (
        <>
          <Alert tone={result.errors?.length ? 'warning' : 'success'}>
            Imported <strong>{result.imported}</strong> of <strong>{result.totalRows}</strong> rows.
          </Alert>
          <AlertList tone="warning" title="Rows that were skipped:" items={result.errors} />
          <AlertList tone="info" title="Notes:" items={result.notes} />
        </>
      ) : (
        <>
          <p className="muted small">
            Upload an Excel sheet (.xlsx or .xls). The first row should hold the column
            headings. The system dynamically reads any structure and lets you configure
            student identification and classmate grouping.
          </p>

          <form onSubmit={analyse}>
            <Field label="Roster file">
              <input
                type="file"
                accept=".xlsx,.xls"
                ref={fileRef}
                onChange={(event) => setFile(event.target.files?.[0] || null)}
              />
            </Field>
            <button type="submit" className="btn btn--primary" disabled={!file || busy}>
              {busy && <span className="spinner" />}
              Analyse the sheet
            </button>
          </form>

          {preview && (
            <>
              <h3 style={{ marginTop: 22 }}>
                1. Student Identification ({preview.totalRows} rows)
              </h3>
              <p className="small muted">
                How should an individual student be uniquely identified? Select a single attribute
                or a combination of attributes (e.g. Roll No, or Class + Roll No).
              </p>

              {(preview.suggestedIdentities || []).length > 0 && (
                <div style={{ marginBottom: 16 }}>
                  {preview.suggestedIdentities.map((suggestion) => {
                    const same =
                      suggestion.attributes.length === chosen.length &&
                      suggestion.attributes.every((item) => chosen.includes(item))
                    return (
                      <label
                        key={suggestion.attributes.join('+')}
                        className={`identity-option ${same ? 'identity-option--selected' : ''}`}
                      >
                        <input
                          type="radio"
                          name="identity-choice"
                          checked={same}
                          onChange={() => setChosen(suggestion.attributes)}
                        />
                        <span className="spread">
                          <strong>{suggestion.label || suggestion.attributes.join(' + ')}</strong>
                          <span className="small muted">
                            {suggestion.attributes.join(' + ')}
                          </span>
                        </span>
                        {suggestion.unique ? (
                          <Badge tone="success">unique</Badge>
                        ) : (
                          <Badge tone="warning">not unique</Badge>
                        )}
                      </label>
                    )
                  })}
                </div>
              )}

              <h3 style={{ marginTop: 22 }}>
                2. Classmate / Academic Group Identification
              </h3>
              <p className="small muted">
                How should students belonging to the same academic group / cohort be identified?
                Students sharing this group will be separated when generating exam arrangements.
              </p>

              {(preview.suggestedClassmateGroups || []).length > 0 && (
                <div style={{ marginBottom: 16 }}>
                  {preview.suggestedClassmateGroups.map((group) => {
                    const same =
                      group.attributes.length === chosenClassmates.length &&
                      group.attributes.every((item) => chosenClassmates.includes(item))
                    return (
                      <label
                        key={group.attributes.join('+')}
                        className={`identity-option ${same ? 'identity-option--selected' : ''}`}
                      >
                        <input
                          type="radio"
                          name="classmate-choice"
                          checked={same}
                          onChange={() => setChosenClassmates(group.attributes)}
                        />
                        <span className="spread">
                          <strong>{group.label || group.attributes.join(' + ')}</strong>
                          <span className="small muted">
                            {group.attributes.join(' + ')}
                          </span>
                        </span>
                        <Badge tone="info">cohort</Badge>
                      </label>
                    )
                  })}
                </div>
              )}

              <h4>All columns in uploaded sheet</h4>
              <p className="small muted">
                Click a column below to add/remove from Unique Student ID. Check the box to add to Classmate Group.
              </p>
              <div className="column-list" style={{ marginTop: 8 }}>
                {columns.map((column) => {
                  const selectedId = chosen.includes(column.key)
                  const selectedGroup = chosenClassmates.includes(column.key)
                  return (
                    <div
                      key={column.key}
                      className={`column ${selectedId ? 'column--selected' : ''}`}
                      onClick={() => toggle(column.key)}
                      style={{ cursor: 'pointer' }}
                    >
                      <div className="column__title">
                        <span>{column.title || column.key}</span>
                        <div className="row" style={{ gap: 4 }}>
                          {selectedId && <Badge tone="primary">ID</Badge>}
                          {selectedGroup && <Badge tone="info">Classmate</Badge>}
                        </div>
                      </div>
                      <div className="column__key">{column.key}</div>
                      <div className="column__samples">
                        {(column.samples || []).join(' · ') || '—'}
                      </div>
                      <div className="row row--between" style={{ marginTop: 8 }} onClick={(e) => e.stopPropagation()}>
                        <label className="checkbox" style={{ fontSize: '0.8rem', margin: 0 }}>
                          <input
                            type="checkbox"
                            checked={selectedGroup}
                            onChange={() => toggleClassmate(column.key)}
                          />
                          Classmate Group
                        </label>
                        <span className="faint small">{column.distinctValues} values</span>
                      </div>
                    </div>
                  )
                })}
              </div>

              {attributes.length === 0 && (
                <p className="small muted" style={{ marginTop: 10 }}>
                  No free-form attributes were found — every column is a standard field.
                </p>
              )}
            </>
          )}
        </>
      )}
    </Modal>
  )
}

/**
 * Configure student identification and classmate grouping for the institution.
 */
function IdentityDialog({ scheme, onClose, onSaved }) {
  const [students, setStudents] = useState([])
  const [selected, setSelected] = useState(scheme?.attributes || [])
  const [selectedClassmates, setSelectedClassmates] = useState(scheme?.classmateAttributes || [])
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    studentApi
      .list({ page: 0, size: 200 })
      .then((page) => setStudents(page?.content || []))
      .catch(() => {})
  }, [])

  /** Every attribute the roster carries, with how many students have it. */
  const attributes = useMemo(() => {
    const counts = new Map()
    students.forEach((student) => {
      Object.entries(student.attributes || {}).forEach(([key, value]) => {
        if (value === null || value === undefined || value === '') return
        counts.set(key, (counts.get(key) || 0) + 1)
      })
      ;['department', 'course', 'yearsession'].forEach((key) => {
        if (student[key]) counts.set(key, (counts.get(key) || 0) + 1)
      })
    })
    return [...counts.entries()]
      .map(([key, count]) => ({ key, count }))
      .sort((a, b) => b.count - a.count || a.key.localeCompare(b.key))
  }, [students])

  function toggle(key) {
    setSelected((current) =>
      current.includes(key) ? current.filter((item) => item !== key) : [...current, key],
    )
  }

  function toggleClassmate(key) {
    setSelectedClassmates((current) =>
      current.includes(key) ? current.filter((item) => item !== key) : [...current, key],
    )
  }

  async function submit() {
    setError(null)
    setBusy(true)
    try {
      await studentApi.saveIdentity({
        label: selected.join(' + '),
        attributes: selected,
        classmateAttributes: selectedClassmates.length ? selectedClassmates : undefined,
      })
      onSaved()
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title="Student Identification & Classmate Grouping"
      wide
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn--primary"
            onClick={submit}
            disabled={busy || selected.length === 0}
          >
            {busy && <span className="spinner" />}
            Save configuration
          </button>
        </>
      }
    >
      {error && <Alert tone="error">{error}</Alert>}
      
      <h3>1. Unique Student Identification</h3>
      <p className="small muted">
        Pick the attribute — or combination of attributes — that uniquely identifies an individual student.
      </p>
      {attributes.length === 0 ? (
        <p className="small muted">
          No attributes were found on the roster yet. Upload a roster first.
        </p>
      ) : (
        <div className="stack" style={{ marginBottom: 20 }}>
          {attributes.map((attribute) => (
            <label
              key={`id-${attribute.key}`}
              className={`identity-option ${
                selected.includes(attribute.key) ? 'identity-option--selected' : ''
              }`}
            >
              <input
                type="checkbox"
                checked={selected.includes(attribute.key)}
                onChange={() => toggle(attribute.key)}
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
      )}

      <h3>2. Classmate / Academic Group Identification</h3>
      <p className="small muted">
        Pick the attribute — or combination of attributes — that defines which students belong to the same academic group (e.g. Class, Section, Stream, Batch). Students in the same group will be separated when generating seating plans.
      </p>
      {attributes.length === 0 ? (
        <p className="small muted">
          No attributes were found on the roster yet.
        </p>
      ) : (
        <div className="stack">
          {attributes.map((attribute) => (
            <label
              key={`cm-${attribute.key}`}
              className={`identity-option ${
                selectedClassmates.includes(attribute.key) ? 'identity-option--selected' : ''
              }`}
            >
              <input
                type="checkbox"
                checked={selectedClassmates.includes(attribute.key)}
                onChange={() => toggleClassmate(attribute.key)}
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
      )}
    </Modal>
  )
}
