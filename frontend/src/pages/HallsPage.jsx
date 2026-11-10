import { useCallback, useEffect, useState } from 'react'
import { roomApi } from '../api/endpoints'
import { Alert, Badge, Card, EmptyState, Field, Loading, Modal, Table } from '../components/ui'

/**
 * The halls of this institution — how many rows and columns each one has,
 * and whether a bench seats one student or two.
 */
export default function HallsPage() {
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const [showAdd, setShowAdd] = useState(false)
  const [editing, setEditing] = useState(null)
  const [showImport, setShowImport] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setData(await roomApi.list({ page: 0, size: 200, sortBy: 'hallName' }))
    } catch (failure) {
      setError(failure.message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function remove(room) {
    if (!window.confirm(`Delete ${room.hallName}? Its saved seats go too.`)) return
    try {
      await roomApi.remove(room.id)
      setNotice(`${room.hallName} was deleted.`)
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
          <h1>Halls &amp; benches</h1>
          <p>
            {data ? `${data.totalElements} hall${data.totalElements === 1 ? '' : 's'}` : 'Loading…'}
          </p>
        </div>
        <div className="btn-row">
          <button type="button" className="btn" onClick={() => setShowImport(true)}>
            Import halls
          </button>
          <button type="button" className="btn btn--primary" onClick={() => setShowAdd(true)}>
            Add a hall
          </button>
        </div>
      </div>

        {error && <Alert tone="error">{error}</Alert>}
        {notice && <Alert tone="success">{notice}</Alert>}

        <Card flush>
          {loading ? (
            <Loading label="Loading halls…" />
          ) : rows.length === 0 ? (
            <EmptyState title="No halls yet">
              Describe a hall — how many rows of benches, how many benches per row, and
              whether a bench seats one or two students.
            </EmptyState>
          ) : (
            <Table
              rows={rows}
              columns={[
                { key: 'hallName', title: 'Hall' },
                { key: 'rows', title: 'Rows', numeric: true },
                { key: 'columns', title: 'Benches per row', numeric: true },
                {
                  key: 'seats',
                  title: 'Seats',
                  numeric: true,
                  render: (room) =>
                    room.rows * room.columns * (room.benchType === 'DUAL_SEATER' ? 2 : 1),
                },
                {
                  key: 'benchType',
                  title: 'Bench',
                  render: (room) => (
                    <Badge tone={room.benchType === 'DUAL_SEATER' ? 'primary' : 'default'}>
                      {room.benchType === 'DUAL_SEATER' ? 'two seats' : 'one seat'}
                    </Badge>
                  ),
                },
                {
                  key: 'actions',
                  title: '',
                  render: (room) => (
                    <div className="btn-row">
                      <button type="button" className="btn btn--sm" onClick={() => setEditing(room)}>
                        Edit
                      </button>
                      <button
                        type="button"
                        className="btn btn--sm btn--danger"
                        onClick={() => remove(room)}
                      >
                        Delete
                      </button>
                    </div>
                  ),
                },
              ]}
            />
          )}
        </Card>
       {(showAdd || editing) && (
        <RoomDialog
          room={editing}
          onClose={() => {
            setShowAdd(false)
            setEditing(null)
          }}
          onSaved={() => {
            setShowAdd(false)
            setEditing(null)
            setNotice('The hall was saved.')
            load()
          }}
        />
      )}

      {showImport && (
        <ImportHallsDialog
          onClose={() => setShowImport(false)}
          onImported={() => {
            setShowImport(false)
            setNotice('The halls were imported.')
            load()
          }}
        />
      )}
    </>
  )
}

function RoomDialog({ room, onClose, onSaved }) {
  const editing = Boolean(room)
  const [form, setForm] = useState({
    hallName: room?.hallName || '',
    rows: room?.rows ?? 5,
    columns: room?.columns ?? 6,
    benchType: room?.benchType || 'SINGLE_SEATER',
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
      const payload = {
        hallName: form.hallName.trim(),
        rows: Number(form.rows),
        columns: Number(form.columns),
        benchType: form.benchType,
      }
      if (editing) {
        await roomApi.patch(room.id, payload)
      } else {
        await roomApi.create(payload)
      }
      onSaved()
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  const seats = Number(form.rows || 0) * Number(form.columns || 0) *
    (form.benchType === 'DUAL_SEATER' ? 2 : 1)

  return (
    <Modal
      title={editing ? `Edit ${room.hallName}` : 'Add a hall'}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" form="room-form" className="btn btn--primary" disabled={busy}>
            {busy && <span className="spinner" />}
            Save
          </button>
        </>
      }
    >
      {error && <Alert tone="error">{error}</Alert>}
      <form id="room-form" onSubmit={submit}>
        <Field label="Hall name">
          <input
            type="text"
            value={form.hallName}
            onChange={(event) => set('hallName', event.target.value)}
            required
            autoFocus
            placeholder="Main Hall A"
          />
        </Field>
        <div className="grid grid--2">
          <Field label="Rows of benches">
            <input
              type="number"
              min="1"
              max="500"
              value={form.rows}
              onChange={(event) => set('rows', event.target.value)}
              required
            />
          </Field>
          <Field label="Benches per row">
            <input
              type="number"
              min="1"
              max="500"
              value={form.columns}
              onChange={(event) => set('columns', event.target.value)}
              required
            />
          </Field>
        </div>
        <Field
          label="Bench type"
          hint="A dual seater puts two students side by side on the same bench — they count as bench mates."
        >
          <select value={form.benchType} onChange={(event) => set('benchType', event.target.value)}>
            <option value="SINGLE_SEATER">Single seater</option>
            <option value="DUAL_SEATER">Dual seater</option>
          </select>
        </Field>
        <p className="small muted">This hall seats {seats} student{seats === 1 ? '' : 's'}.</p>
      </form>
    </Modal>
  )
}

function ImportHallsDialog({ onClose, onImported }) {
  const [file, setFile] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)

  async function submit(event) {
    event.preventDefault()
    if (!file) return
    setError(null)
    setBusy(true)
    try {
      const outcome = await roomApi.import(file)
      setResult(outcome)
      if (!outcome.errors || outcome.errors.length === 0) {
        onImported(`Imported ${outcome.imported} halls.`)
      }
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title="Import halls"
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
            <button type="submit" form="halls-form" className="btn btn--primary" disabled={!file || busy}>
              {busy && <span className="spinner" />}
              Import
            </button>
          </>
        )
      }
    >
      {error && <Alert tone="error">{error}</Alert>}
      {result ? (
        <>
          <Alert tone={result.errors?.length ? 'warning' : 'success'}>
            Imported <strong>{result.imported}</strong> halls.
          </Alert>
          {result.errors?.length > 0 && (
            <ul className="alert__list">
              {result.errors.map((item, index) => (
                <li key={index}>{item}</li>
              ))}
            </ul>
          )}
        </>
      ) : (
        <form id="halls-form" onSubmit={submit}>
          <p className="small muted">
            An Excel sheet with one row per hall — the hall name, the number of rows, the
            number of benches per row and the bench type.
          </p>
          <Field label="Hall file">
            <input
              type="file"
              accept=".xlsx,.xls"
              onChange={(event) => setFile(event.target.files?.[0] || null)}
            />
          </Field>
        </form>
      )}
    </Modal>
  )
}
