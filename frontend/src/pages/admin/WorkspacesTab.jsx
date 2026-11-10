import { useMemo, useState } from 'react'
import { adminApi } from '../../api/endpoints'
import { Alert, Badge, Field, Modal, Table, bytes, moment } from '../../components/ui'
import { IconSearch } from '../../components/Icons'

const LIMIT_FIELDS = [
  { key: 'maxStudents', label: 'Students', unit: '' },
  { key: 'maxRooms', label: 'Halls', unit: '' },
  { key: 'maxGenerationsPerMonth', label: 'Plans per month', unit: '' },
  { key: 'storageLimitMb', label: 'Storage', unit: 'MB' },
  { key: 'maxFileSizeMb', label: 'Largest upload', unit: 'MB' },
  { key: 'seatsPerGeneration', label: 'Seats per plan', unit: '' },
]

/**
 * Every workspace: its plan, its usage and the limits it works under.
 */
export default function WorkspacesTab({ institutions, plans, onChanged }) {
  const [search, setSearch] = useState('')
  const [editing, setEditing] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase()
    if (!needle) return institutions
    return institutions.filter(
      (institution) =>
        institution.name?.toLowerCase().includes(needle) ||
        institution.slug?.toLowerCase().includes(needle) ||
        institution.contactEmail?.toLowerCase().includes(needle),
    )
  }, [institutions, search])

  async function run(action) {
    setError(null)
    setBusy(true)
    try {
      await action()
      await onChanged()
    } catch (failure) {
      setError(failure.message || 'The change could not be saved')
    } finally {
      setBusy(false)
    }
  }

  const columns = [
    {
      key: 'name',
      title: 'Workspace',
      render: (institution) => (
        <div style={{ minWidth: 0 }}>
          <div className="user-cell__name">{institution.name}</div>
          <div className="user-cell__meta">
            {institution.contactEmail || 'No address'} · {institution.usersCount}{' '}
            {institution.usersCount === 1 ? 'account' : 'accounts'}
          </div>
        </div>
      ),
    },
    {
      key: 'plan',
      title: 'Plan',
      render: (institution) => (
        <Badge tone="brand">{institution.planName}</Badge>
      ),
    },
    {
      key: 'students',
      title: 'Students',
      numeric: true,
      render: (institution) => (
        <span className="nowrap">
          {institution.studentsUsed} / {institution.studentsLimit}
        </span>
      ),
    },
    {
      key: 'rooms',
      title: 'Halls',
      numeric: true,
      render: (institution) => (
        <span className="nowrap">
          {institution.roomsUsed} / {institution.roomsLimit}
        </span>
      ),
    },
    {
      key: 'generations',
      title: 'Plans',
      numeric: true,
      render: (institution) => (
        <span className="nowrap">
          {institution.generationsUsed} / {institution.generationsLimit}
        </span>
      ),
    },
    {
      key: 'storage',
      title: 'Storage',
      numeric: true,
      render: (institution) => (
        <span className="nowrap small muted">
          {bytes(institution.storageUsedBytes)} / {bytes(institution.storageLimitBytes)}
        </span>
      ),
    },
    {
      key: 'status',
      title: 'Status',
      render: (institution) => (
        <Badge tone={institution.status === 'ACTIVE' ? 'ok' : 'bad'}>{institution.status}</Badge>
      ),
    },
    {
      key: 'actions',
      title: '',
      render: (institution) => (
        <div className="row nowrap" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn--sm" onClick={() => setEditing(institution)}>
            Manage
          </button>
        </div>
      ),
    },
  ]

  return (
    <div className="stack">
      {error && <Alert tone="error">{error}</Alert>}

      <section className="card">
        <div className="toolbar">
          <div style={{ position: 'relative', flex: '1 1 240px', maxWidth: 340 }}>
            <span
              style={{
                position: 'absolute',
                left: 10,
                top: '50%',
                transform: 'translateY(-50%)',
                color: 'var(--text-faint)',
                pointerEvents: 'none',
                display: 'inline-flex',
              }}
            >
              <IconSearch width={16} height={16} />
            </span>
            <input
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Search by name, handle or address"
              style={{ paddingLeft: 34 }}
            />
          </div>
          <span className="toolbar__spacer" />
          <span className="muted small">
            {rows.length} of {institutions.length} workspaces
          </span>
        </div>

        <Table columns={columns} rows={rows} empty="No workspace matches that search." />
      </section>

      {editing && (
        <ManageWorkspaceDialog
          institution={editing}
          plans={plans}
          busy={busy}
          onClose={() => setEditing(null)}
          onSave={(body) =>
            run(async () => {
              await adminApi.changeSubscription(editing.id, body)
              setEditing(null)
            })
          }
          onToggleStatus={() =>
            run(async () => {
              const next = editing.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'
              await adminApi.changeStatus(editing.id, next)
              setEditing(null)
            })
          }
        />
      )}
    </div>
  )
}

/** The "manage a workspace" dialog: its plan and its limits. */
function ManageWorkspaceDialog({ institution, plans, busy, onClose, onSave, onToggleStatus }) {
  const [planCode, setPlanCode] = useState(institution.planCode)
  const [limits, setLimits] = useState({
    maxStudents: institution.overrides?.maxStudents ?? '',
    maxRooms: institution.overrides?.maxRooms ?? '',
    maxGenerationsPerMonth: institution.overrides?.maxGenerationsPerMonth ?? '',
    storageLimitMb: institution.overrides?.storageLimitMb ?? '',
    maxFileSizeMb: institution.overrides?.maxFileSizeMb ?? '',
    seatsPerGeneration: institution.overrides?.seatsPerGeneration ?? '',
  })

  const plan = plans.find((entry) => entry.code === planCode)

  function set(key, value) {
    setLimits((current) => ({ ...current, [key]: value }))
  }

  return (
    <Modal
      title={institution.name}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn--danger"
            onClick={onToggleStatus}
            disabled={busy}
          >
            {institution.status === 'ACTIVE' ? 'Suspend' : 'Restore'}
          </button>
          <button
            type="button"
            className="btn btn--primary"
            disabled={busy}
            onClick={() => {
              const body = { planCode, limits: {} }
              LIMIT_FIELDS.forEach(({ key }) => {
                const raw = limits[key]
                body.limits[key] = raw === '' || raw === null ? null : Number(raw)
              })
              onSave(body)
            }}
          >
            {busy ? 'Saving…' : 'Save'}
          </button>
        </>
      }
    >
      <div className="stack">
        <div className="muted small">
          Created {moment(institution.createdAt)} · handle{' '}
          <code>{institution.slug}</code>
        </div>

        <Field label="Plan">
          <select value={planCode} onChange={(event) => setPlanCode(event.target.value)}>
            {plans.map((entry) => (
              <option key={entry.code} value={entry.code}>
                {entry.name}
              </option>
            ))}
          </select>
        </Field>

        <div className="divider" />

        <p className="muted small" style={{ margin: 0 }}>
          Limits that override the plan. Leave a field empty to use the plan's
          number.
        </p>

        {LIMIT_FIELDS.map((field) => {
          const planValue = plan ? plan[field.key] : undefined
          return (
            <Field
              key={field.key}
              label={`${field.label}${field.unit ? ` (${field.unit})` : ''}`}
              hint={
                planValue !== undefined
                  ? `The plan allows ${planValue}${field.unit ? ` ${field.unit}` : ''}.`
                  : undefined
              }
            >
              <input
                type="number"
                min="0"
                value={limits[field.key]}
                onChange={(event) => set(field.key, event.target.value)}
                placeholder={planValue ?? ''}
              />
            </Field>
          )
        })}
      </div>
    </Modal>
  )
}
