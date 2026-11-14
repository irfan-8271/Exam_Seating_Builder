import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { cohortApi, studentApi } from '../api/endpoints'
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
import { CohortBuckets } from '../components/CohortBuckets'

function normalizeKey(raw) {
  return (raw || '').trim().toLowerCase().replace(/[^a-z0-9]/g, '')
}

function readStudentValue(student, key) {
  if (!student || !key) return ''
  const norm = normalizeKey(key)
  if (norm === 'name') return student.name || ''
  const attrs = student.attributes || {}
  if (attrs[norm] !== undefined && attrs[norm] !== null) return String(attrs[norm])
  const matchKey = Object.keys(attrs).find((k) => normalizeKey(k) === norm)
  return matchKey ? String(attrs[matchKey] ?? '') : ''
}

function formatIdentity(student, scheme) {
  if (scheme?.attributes?.length) {
    const parts = scheme.attributes
      .map((key) => readStudentValue(student, key))
      .filter(Boolean)
    if (parts.length) {
      return parts.join(' · ')
    }
  }
  if (student?.identityKey) {
    return student.identityKey.replace(/\|/g, ' · ')
  }
  return '—'
}

function formatCohort(student, classmateAttributes) {
  if (!classmateAttributes?.length) return ''
  return classmateAttributes
    .map((key) => readStudentValue(student, key))
    .filter(Boolean)
    .join(' · ')
}

function columnTitleMap(schema) {
  const map = new Map()
  ;(schema?.columns || []).forEach((col) => {
    map.set(col.key, col.title || col.key)
  })
  return map
}

function describeKeys(keys, titleMap) {
  if (!keys?.length) return ''
  return keys.map((k) => titleMap.get(k) || k).join(' + ')
}

export default function StudentsPage() {
  const [page, setPage] = useState(0)
  const [size] = useState(25)
  const [search, setSearch] = useState('')
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const [scheme, setScheme] = useState(null)
  const [rosterSchema, setRosterSchema] = useState(null)
  const [showAdd, setShowAdd] = useState(false)
  const [showImport, setShowImport] = useState(false)
  const [editing, setEditing] = useState(null)
  const [showIdentity, setShowIdentity] = useState(false)
  const [cohorts, setCohorts] = useState(null)
  const [cohortsLoading, setCohortsLoading] = useState(true)
  const [viewMode, setViewMode] = useState('categories')

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [students, identity, schema] = await Promise.all([
        studentApi.list({ page, size, q: search.trim() || undefined }),
        studentApi.identity().catch(() => null),
        studentApi.schema().catch(() => null),
      ])
      setData(students)
      setScheme(identity)
      setRosterSchema(schema)
    } catch (failure) {
      setError(failure.message)
    } finally {
      setLoading(false)
    }
  }, [page, size, search])

  useEffect(() => {
    load()
  }, [load])

  const loadCohorts = useCallback(async () => {
    setCohortsLoading(true)
    try {
      const result = await cohortApi.overview().catch(() => null)
      setCohorts(result)
    } catch {
      setCohorts(null)
    } finally {
      setCohortsLoading(false)
    }
  }, [])

  useEffect(() => {
    loadCohorts()
  }, [loadCohorts])

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
  const totalStudents = data?.totalElements || 0
  const titleMap = useMemo(() => columnTitleMap(rosterSchema), [rosterSchema])

  const tableColumns = useMemo(() => {
    const idAttrs = scheme?.attributes || []
    const classmateAttrs = scheme?.classmateAttributes || []
    const cols = [
      {
        key: 'identity',
        title: idAttrs.length ? `Student ID (${describeKeys(idAttrs, titleMap)})` : 'Student ID',
        render: (row) => <strong>{formatIdentity(row, scheme)}</strong>,
      },
      { key: 'name', title: 'Student Name' },
    ]

    if (classmateAttrs.length) {
      cols.push({
        key: '__cohort',
        title: `Classmate Cohort (${describeKeys(classmateAttrs, titleMap)})`,
        render: (row) => {
          const cohort = formatCohort(row, classmateAttrs)
          return cohort ? <Badge tone="info">{cohort}</Badge> : <span className="faint">—</span>
        },
      })
    }

    const schemaCols = (rosterSchema?.columns || []).filter((col) => {
      if (col.key === 'name') return false
      if (idAttrs.length === 1 && idAttrs[0] === col.key) return false
      return true
    })

    schemaCols.slice(0, 5).forEach((col) => {
      cols.push({
        key: `attr-${col.key}`,
        title: col.title || col.key,
        render: (row) => {
          const val = readStudentValue(row, col.key)
          return val ? val : <span className="faint">—</span>
        },
      })
    })

    cols.push({
      key: 'actions',
      title: '',
      render: (row) => (
        <div className="btn-row" onClick={(event) => event.stopPropagation()}>
          <button type="button" className="btn btn--sm" onClick={() => setEditing(row)}>
            Edit
          </button>
          <button type="button" className="btn btn--sm btn--danger" onClick={() => remove(row)}>
            Remove
          </button>
        </div>
      ),
    })

    return cols
  }, [scheme, rosterSchema, titleMap])

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Students</h1>
          <p>
            {data ? `${totalStudents} student${totalStudents === 1 ? '' : 's'}` : 'Loading…'}
            {scheme?.attributes?.length
              ? ` · ID: ${describeKeys(scheme.attributes, titleMap)}`
              : ' · schema adapts automatically to your spreadsheet'}
          </p>
        </div>
        <div className="btn-row">
          {(totalStudents > 0 || scheme?.attributes?.length > 0) && (
            <button type="button" className="btn" onClick={() => setShowIdentity(true)}>
              Configure ID &amp; cohorts
            </button>
          )}
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
        <Alert tone="info" title="Institution Roster Schema">
          <div>
            <strong>Unique Student ID:</strong> {describeKeys(scheme.attributes, titleMap)}
          </div>
          {scheme.classmateAttributes?.length ? (
            <div style={{ marginTop: 4 }}>
              <strong>Classmate Cohort (separated in exams):</strong>{' '}
              {describeKeys(scheme.classmateAttributes, titleMap)}
            </div>
          ) : (
            <div className="small muted" style={{ marginTop: 4 }}>
              No classmate cohort rule saved yet — configure one so classmates are separated automatically.
            </div>
          )}
          {scheme.scopeAttribute && (
            <div style={{ marginTop: 4 }}>
              <strong>Exam Round Partition Field:</strong>{' '}
              {titleMap.get(scheme.scopeAttribute) || scheme.scopeAttribute}
            </div>
          )}
          <div className="btn-row" style={{ marginTop: 8 }}>
            <button type="button" className="btn btn--sm" onClick={() => setShowIdentity(true)}>
              Edit ID &amp; cohort rules
            </button>
            <button type="button" className="btn btn--sm" onClick={clearIdentity}>
              Reset to automatic detection
            </button>
          </div>
        </Alert>
      ) : totalStudents > 0 ? (
        <Alert tone="info" title="Automatic Schema Discovery">
          <div className="small">
            Discovered {rosterSchema?.columns?.length || 0} dynamic attribute
            {(rosterSchema?.columns?.length || 0) === 1 ? '' : 's'} from your roster. Choose which
            attribute(s) uniquely identify each student and which define classmates.
          </div>
          <div className="btn-row" style={{ marginTop: 8 }}>
            <button type="button" className="btn btn--sm" onClick={() => setShowIdentity(true)}>
              Configure ID &amp; cohort rules
            </button>
          </div>
        </Alert>
      ) : null}

      <div className="row row--between" style={{ marginBottom: 12, gap: 12, flexWrap: 'wrap' }}>
        <input
          type="search"
          placeholder="Search students by name, ID, or any dynamic attribute…"
          value={search}
          onChange={(e) => {
            setSearch(e.target.value)
            setPage(0)
          }}
          style={{ maxWidth: 420 }}
        />
        <div className="btn-row" role="group" aria-label="Roster view">
          <button
            type="button"
            className={`btn btn--sm ${viewMode === 'categories' ? 'btn--primary' : ''}`}
            onClick={() => setViewMode('categories')}
          >
            Categories
          </button>
          <button
            type="button"
            className={`btn btn--sm ${viewMode === 'list' ? 'btn--primary' : ''}`}
            onClick={() => setViewMode('list')}
          >
            All students
          </button>
        </div>
      </div>

      {viewMode === 'categories' && (
        <Card
          title="Student categories"
          subtitle="Categories are grouped automatically from your classmate rule. Use “Configure cohort” inside any category to split it further."
          flush
        >
          <div style={{ padding: 12 }}>
            <CohortBuckets
              overview={cohorts}
              loading={cohortsLoading}
              onRefresh={loadCohorts}
              titleMap={titleMap}
              renderMembers={(bucket) => (
                <StudentMembers
                  bucket={bucket}
                  attributes={cohorts?.attributes || []}
                  onEdit={setEditing}
                />
              )}
            />
          </div>
        </Card>
      )}

      {viewMode === 'list' && (
      <Card flush>
        {loading ? (
          <Loading label="Loading students…" />
        ) : rows.length === 0 ? (
          <EmptyState title="No students found">
            Upload an Excel roster with any columns your school, college, or university uses, or add
            students manually.
          </EmptyState>
        ) : (
          <>
            <Table rows={rows} onRowClick={(row) => setEditing(row)} columns={tableColumns} />
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
      )}

      {showAdd && (
        <StudentDialog
          title="Add a student"
          scheme={scheme}
          rosterSchema={rosterSchema}
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
          scheme={scheme}
          rosterSchema={rosterSchema}
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
          rosterSchema={rosterSchema}
          onClose={() => setShowIdentity(false)}
          onSaved={() => {
            setShowIdentity(false)
            setNotice('Student ID and classmate cohort rules updated.')
            load()
          }}
        />
      )}
    </>
  )
}

/**
 * The students inside one expanded category, with a quick edit shortcut so a
 * student sitting in Miscellaneous can be corrected straight away.
 */
function StudentMembers({ bucket, attributes, onEdit }) {
  const [members, setMembers] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    cohortApi
      .members(attributes, bucket.values)
      .then((result) => {
        if (!cancelled) setMembers(result)
      })
      .catch((failure) => {
        if (!cancelled) setError(failure.message)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [attributes, bucket.values])

  if (loading) return <Loading label="Loading students…" />
  if (error) return <Alert tone="error">{error}</Alert>
  if (!members?.length) return <div className="small muted">No students in this category.</div>

  return (
    <div className="cohort-members">
      {members.map((student) => (
        <div key={student.id} className="cohort-members__row">
          <span className="cohort-members__name">{student.name}</span>
          <span className="small muted cohort-members__id">
            {student.identityKey ? student.identityKey.replace(/\|/g, ' · ') : '—'}
          </span>
          <button type="button" className="btn btn--sm" onClick={() => onEdit(student)}>
            Edit
          </button>
        </div>
      ))}
    </div>
  )
}

function StudentDialog({ title, student, scheme, rosterSchema, onClose, onSaved }) {
  const editing = Boolean(student)
  const idKeys = scheme?.attributes || []
  const classmateKeys = scheme?.classmateAttributes || []

  const schemaFields = useMemo(() => {
    const byKey = new Map()
    ;(rosterSchema?.columns || []).forEach((col) => {
      if (col.key && col.key !== 'name') {
        byKey.set(col.key, col.title || col.key)
      }
    })
    idKeys.forEach((key) => {
      if (key !== 'name' && !byKey.has(key)) byKey.set(key, key)
    })
    classmateKeys.forEach((key) => {
      if (key !== 'name' && !byKey.has(key)) byKey.set(key, key)
    })
    if (scheme?.scopeAttribute && !byKey.has(scheme.scopeAttribute)) {
      byKey.set(scheme.scopeAttribute, scheme.scopeAttribute)
    }
    return [...byKey.entries()].map(([key, label]) => ({
      key,
      label,
      isId: idKeys.includes(key),
      isClassmate: classmateKeys.includes(key),
    }))
  }, [rosterSchema, idKeys, classmateKeys, scheme])

  const hasDiscoveredSchema = schemaFields.length > 0
  const [name, setName] = useState(student?.name || '')
  const [fieldValues, setFieldValues] = useState(() => {
    const initial = {}
    schemaFields.forEach(({ key }) => {
      initial[key] = readStudentValue(student, key)
    })
    return initial
  })

  const [extra, setExtra] = useState(() => {
    const knownKeys = new Set(schemaFields.map((f) => f.key))
    const entries = Object.entries(student?.attributes || {}).filter(
      ([k]) => !knownKeys.has(normalizeKey(k)),
    )
    if (!hasDiscoveredSchema && !editing && entries.length === 0) {
      return [
        ['studentid', ''],
        ['cohort', ''],
      ]
    }
    return entries
  })

  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  function setField(key, value) {
    setFieldValues((prev) => ({ ...prev, [key]: value }))
  }

  function addSuggestedAttr(attrKey) {
    if (extra.some(([k]) => normalizeKey(k) === attrKey)) return
    setExtra((prev) => [...prev, [attrKey, '']])
  }

  async function submit(event) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      const attributes = {}
      schemaFields.forEach(({ key }) => {
        const val = (fieldValues[key] || '').trim()
        if (val) attributes[key] = val
      })
      extra.forEach(([rawKey, rawValue]) => {
        const cleanKey = normalizeKey(rawKey)
        const cleanVal = (rawValue || '').trim()
        if (cleanKey && cleanKey !== 'name' && cleanVal) {
          attributes[cleanKey] = cleanVal
        }
      })

      const payload = {
        name: name.trim(),
        attributes,
      }

      if (editing) {
        await studentApi.replace(student.id, payload)
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
        <Field label="Student Name">
          <input
            type="text"
            value={name}
            onChange={(event) => setName(event.target.value)}
            required
            autoFocus
          />
        </Field>

        {hasDiscoveredSchema && (
          <div className="grid grid--2">
            {schemaFields.map((field) => (
              <Field
                key={field.key}
                label={
                  <span className="row" style={{ gap: 6 }}>
                    <span>{field.label}</span>
                    {field.isId && <Badge tone="primary">ID</Badge>}
                    {field.isClassmate && <Badge tone="info">Cohort</Badge>}
                  </span>
                }
                hint={field.isId ? 'Part of Unique Student ID' : undefined}
              >
                <input
                  type="text"
                  value={fieldValues[field.key] || ''}
                  required={field.isId}
                  onChange={(event) => setField(field.key, event.target.value)}
                />
              </Field>
            ))}
          </div>
        )}

        <fieldset style={{ marginTop: 12 }}>
          <legend>{hasDiscoveredSchema ? 'Additional custom attributes' : 'Student attributes'}</legend>
          <p className="small muted">
            {hasDiscoveredSchema
              ? 'Add any extra attribute your institution tracks.'
              : 'Add whichever fields your institution uses (for example: admissionno, grade, division, stream, batch, examdate).'}
          </p>

          {!hasDiscoveredSchema && (
            <div className="btn-row" style={{ marginBottom: 10, flexWrap: 'wrap' }}>
              {['admissionno', 'grade', 'division', 'stream', 'batch', 'examdate'].map((preset) => (
                <button
                  key={preset}
                  type="button"
                  className="btn btn--sm"
                  onClick={() => addSuggestedAttr(preset)}
                >
                  + {preset}
                </button>
              ))}
            </div>
          )}

          {extra.map(([key, value], index) => (
            <div className="row" key={index} style={{ marginBottom: 8 }}>
              <input
                type="text"
                placeholder="attribute name (e.g. grade, batch)"
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
            Add attribute
          </button>
        </fieldset>
      </form>
    </Modal>
  )
}

function ImportDialog({ onClose, onImported }) {
  const fileRef = useRef(null)
  const [file, setFile] = useState(null)
  const [preview, setPreview] = useState(null)
  const [chosen, setChosen] = useState([])
  const [chosenClassmates, setChosenClassmates] = useState([])
  const [chosenScope, setChosenScope] = useState('')
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
      setChosen(
        data.currentScheme?.attributes?.length
          ? data.currentScheme.attributes
          : first
          ? first.attributes
          : [],
      )

      const suggestedGroups = data.suggestedClassmateGroups || []
      const firstGroup = suggestedGroups[0]
      setChosenClassmates(
        data.currentScheme?.classmateAttributes?.length
          ? data.currentScheme.classmateAttributes
          : firstGroup
          ? firstGroup.attributes
          : [],
      )
      setChosenScope(data.currentScheme?.scopeAttribute || '')
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
      const outcome = await studentApi.import(file, chosen, chosenClassmates, chosenScope || null)
      setResult(outcome)
      if (!outcome.errors || outcome.errors.length === 0) {
        const titleByKey = new Map(
          (preview?.columns || []).map((col) => [col.key, col.title || col.key]),
        )
        if (chosen.length > 0) {
          await studentApi
            .saveIdentity({
              label: chosen.map((k) => titleByKey.get(k) || k).join(' + '),
              attributes: chosen,
              classmateAttributes: chosenClassmates,
              scopeAttribute: chosenScope || null,
            })
            .catch(() => {})
        }
        onImported(`Imported ${outcome.imported} of ${outcome.totalRows} students.`)
      }
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  const columns = (preview?.columns || []).filter((col) => col.key !== 'name')

  return (
    <Modal
      title="Upload a student roster"
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
          <AlertList tone="warning" title="Rows skipped:" items={result.errors} />
          <AlertList tone="info" title="Notes:" items={result.notes} />
        </>
      ) : (
        <>
          <p className="muted small">
            Upload any Excel sheet (.xlsx or .xls). Every column is preserved dynamically — select
            which column(s) uniquely identify each student and which define classmates.
          </p>

          <form onSubmit={analyse}>
            <Field label="Roster workbook">
              <input
                type="file"
                accept=".xlsx,.xls"
                ref={fileRef}
                onChange={(event) => setFile(event.target.files?.[0] || null)}
              />
            </Field>
            <button type="submit" className="btn btn--primary" disabled={!file || busy}>
              {busy && <span className="spinner" />}
              Inspect columns
            </button>
          </form>

          {preview && (
            <>
              <h3 style={{ marginTop: 22 }}>
                1. Unique Student ID ({preview.totalRows} rows)
              </h3>
              <p className="small muted">
                Pick one column or a multi-column composite (e.g. Grade + Division + Roll No) that is
                unique across all students.
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
                          <strong>{suggestion.label}</strong>
                          <span className="small muted">{suggestion.attributes.join(' + ')}</span>
                        </span>
                        <Badge tone={suggestion.unique ? 'success' : 'warning'}>
                          {suggestion.unique ? 'unique' : 'not unique'}
                        </Badge>
                      </label>
                    )
                  })}
                </div>
              )}

              <h3 style={{ marginTop: 22 }}>2. Classmate / Cohort Attributes</h3>
              <p className="small muted">
                Pick the attribute(s) shared by classmates who should not sit together.
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
                          <strong>{group.label}</strong>
                          <span className="small muted">{group.attributes.join(' + ')}</span>
                        </span>
                        <Badge tone="info">cohort</Badge>
                      </label>
                    )
                  })}
                </div>
              )}

              <Field
                label="3. Optional Exam Round Partition Column (e.g. Exam Date, Paper, Shift)"
                hint="Leave blank if all imported students sit together in one round."
              >
                <select
                  value={chosenScope}
                  onChange={(e) => setChosenScope(e.target.value)}
                >
                  <option value="">None (whole roster)</option>
                  {columns
                    .filter((c) => !c.unique)
                    .map((c) => (
                      <option key={c.key} value={c.key}>
                        {c.title} ({c.distinctValues} values)
                      </option>
                    ))}
                </select>
              </Field>

              <h4>All discovered columns</h4>
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
                          {selectedGroup && <Badge tone="info">Cohort</Badge>}
                        </div>
                      </div>
                      <div className="column__key">{column.key}</div>
                      <div className="column__samples">
                        {(column.samples || []).join(' · ') || '—'}
                      </div>
                      <div
                        className="row row--between"
                        style={{ marginTop: 8 }}
                        onClick={(e) => e.stopPropagation()}
                      >
                        <label className="checkbox" style={{ fontSize: '0.8rem', margin: 0 }}>
                          <input
                            type="checkbox"
                            checked={selectedGroup}
                            onChange={() => toggleClassmate(column.key)}
                          />
                          Classmate Cohort
                        </label>
                        <span className="faint small">{column.distinctValues} values</span>
                      </div>
                    </div>
                  )
                })}
              </div>
            </>
          )}
        </>
      )}
    </Modal>
  )
}

function IdentityDialog({ scheme, rosterSchema: initialSchema, onClose, onSaved }) {
  const [schema, setSchema] = useState(initialSchema)
  const [selected, setSelected] = useState(scheme?.attributes || [])
  const [selectedClassmates, setSelectedClassmates] = useState(scheme?.classmateAttributes || [])
  const [scopeAttribute, setScopeAttribute] = useState(scheme?.scopeAttribute || '')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    studentApi
      .schema()
      .then((fresh) => {
        setSchema(fresh)
        if (!selected.length && fresh?.currentIdentity?.length) {
          setSelected(fresh.currentIdentity)
        } else if (!selected.length && fresh?.suggestedIdentities?.length) {
          setSelected(fresh.suggestedIdentities[0].attributes)
        }
        if (!scopeAttribute && fresh?.currentScopeAttribute) {
          setScopeAttribute(fresh.currentScopeAttribute)
        }
      })
      .catch(() => {})
  }, [])

  const columns = useMemo(
    () => (schema?.columns || []).filter((col) => col.key !== 'name'),
    [schema],
  )
  const titleMap = useMemo(() => columnTitleMap(schema), [schema])

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
        label: describeKeys(selected, titleMap),
        attributes: selected,
        classmateAttributes: selectedClassmates,
        scopeAttribute: scopeAttribute || '',
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
      title="Student ID, Classmate Cohorts & Exam Scope"
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
        Choose one attribute or a multi-attribute combination that uniquely identifies every student
        ({schema?.totalStudents || 0} on file). Saving validates uniqueness and re-keys all students
        atomically.
      </p>

      {(schema?.suggestedIdentities || []).length > 0 && (
        <div style={{ marginBottom: 14 }}>
          <div className="btn-row" style={{ flexWrap: 'wrap' }}>
            {schema.suggestedIdentities.map((item) => {
              const active =
                item.attributes.length === selected.length &&
                item.attributes.every((k) => selected.includes(k))
              return (
                <button
                  key={item.attributes.join('+')}
                  type="button"
                  className={`btn btn--sm ${active ? 'btn--primary' : ''}`}
                  onClick={() => setSelected(item.attributes)}
                >
                  {item.label}
                </button>
              )
            })}
          </div>
        </div>
      )}

      <div className="stack" style={{ marginBottom: 22 }}>
        {columns.map((col) => (
          <label
            key={`id-${col.key}`}
            className={`identity-option ${
              selected.includes(col.key) ? 'identity-option--selected' : ''
            }`}
          >
            <input
              type="checkbox"
              checked={selected.includes(col.key)}
              onChange={() => toggle(col.key)}
            />
            <span className="spread">
              <strong>{col.title || col.key}</strong>
              <span className="small muted">
                {col.distinctValues} distinct · {(col.samples || []).slice(0, 2).join(', ') || '—'}
              </span>
            </span>
            {col.unique ? (
              <Badge tone="success">unique alone</Badge>
            ) : !col.complete ? (
              <Badge tone="warning">has blanks</Badge>
            ) : null}
          </label>
        ))}
      </div>

      <h3>2. Classmate / Cohort Separation Attributes</h3>
      <p className="small muted">
        Students sharing these attribute(s) are treated as classmates and kept apart across benches
        and rows.
      </p>

      {(schema?.suggestedClassmateGroups || []).length > 0 && (
        <div style={{ marginBottom: 14 }}>
          <div className="btn-row" style={{ flexWrap: 'wrap' }}>
            {schema.suggestedClassmateGroups.map((group) => {
              const active =
                group.attributes.length === selectedClassmates.length &&
                group.attributes.every((k) => selectedClassmates.includes(k))
              return (
                <button
                  key={group.attributes.join('+')}
                  type="button"
                  className={`btn btn--sm ${active ? 'btn--primary' : ''}`}
                  onClick={() => setSelectedClassmates(group.attributes)}
                >
                  {group.label}
                </button>
              )
            })}
          </div>
        </div>
      )}

      <div className="stack" style={{ marginBottom: 22 }}>
        {columns.map((col) => (
          <label
            key={`cm-${col.key}`}
            className={`identity-option ${
              selectedClassmates.includes(col.key) ? 'identity-option--selected' : ''
            }`}
          >
            <input
              type="checkbox"
              checked={selectedClassmates.includes(col.key)}
              onChange={() => toggleClassmate(col.key)}
            />
            <span className="spread">
              <strong>{col.title || col.key}</strong>
              <span className="small muted">{col.distinctValues} distinct values</span>
            </span>
          </label>
        ))}
      </div>

      <Field
        label="3. Optional Exam Round Partition Attribute"
        hint="Used when generating separate seating rounds by Exam Date, Paper, or Shift."
      >
        <select
          value={scopeAttribute}
          onChange={(e) => setScopeAttribute(e.target.value)}
        >
          <option value="">None (arrange whole roster)</option>
          {columns
            .filter((c) => !c.unique)
            .map((c) => (
              <option key={c.key} value={c.key}>
                {c.title} ({c.distinctValues} values)
              </option>
            ))}
        </select>
      </Field>
    </Modal>
  )
}
