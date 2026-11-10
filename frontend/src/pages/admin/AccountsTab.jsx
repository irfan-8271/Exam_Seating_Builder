import { useMemo, useState } from 'react'
import { adminApi } from '../../api/endpoints'
import { Alert, Badge, Field, Modal, Table, moment } from '../../components/ui'
import { IconKey, IconSearch, IconShield } from '../../components/Icons'
import { initials } from './OverviewTab'

const ROLE_TONE = {
  PLATFORM_ADMIN: 'accent',
  ADMIN: 'brand',
  EXAM_CELL: 'default',
  INVIGILATOR: 'default',
}

const LIMIT_FIELDS = [
  { key: 'maxStudents', label: 'Students', unit: '' },
  { key: 'maxRooms', label: 'Halls', unit: '' },
  { key: 'maxGenerationsPerMonth', label: 'Plans per month', unit: '' },
  { key: 'storageLimitMb', label: 'Storage', unit: 'MB' },
  { key: 'maxFileSizeMb', label: 'Largest upload', unit: 'MB' },
  { key: 'seatsPerGeneration', label: 'Seats per plan', unit: '' },
]

/**
 * Every account of the service.
 *
 * <p>The operator can change a role, suspend or restore an account, move it
 * to another workspace, reset its password and give it limits of its own.
 * There is no way to read a password from here, and there never will be.</p>
 */
export default function AccountsTab({ users, plans, onChanged }) {
  const [search, setSearch] = useState('')
  const [roleFilter, setRoleFilter] = useState('ALL')
  const [editing, setEditing] = useState(null)
  const [limiting, setLimiting] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  const roles = useMemo(
    () => ['ALL', ...new Set(users.map((user) => user.role))].sort(),
    [users],
  )

  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return users.filter((user) => {
      if (roleFilter !== 'ALL' && user.role !== roleFilter) return false
      if (!needle) return true
      return (
        user.username?.toLowerCase().includes(needle) ||
        user.fullName?.toLowerCase().includes(needle) ||
        user.email?.toLowerCase().includes(needle) ||
        user.institutionName?.toLowerCase().includes(needle)
      )
    })
  }, [users, search, roleFilter])

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
      key: 'account',
      title: 'Account',
      render: (user) => (
        <div className="user-cell">
          <span className="avatar">{initials(user)}</span>
          <div style={{ minWidth: 0 }}>
            <div className="user-cell__name">{user.username}</div>
            <div className="user-cell__meta">
              {user.fullName || user.email || '—'} · id {user.id}
            </div>
          </div>
        </div>
      ),
    },
    {
      key: 'role',
      title: 'Role',
      render: (user) => (
        <Badge tone={ROLE_TONE[user.role] || 'default'}>{user.role.replace('_', ' ')}</Badge>
      ),
    },
    { key: 'institutionName', title: 'Workspace', render: (user) => user.institutionName || '—' },
    {
      key: 'status',
      title: 'Status',
      render: (user) => (
        <Badge tone={user.status === 'ACTIVE' ? 'ok' : 'bad'}>{user.status}</Badge>
      ),
    },
    {
      key: 'limits',
      title: 'Limits',
      render: (user) => (
        <span className="small muted nowrap">
          {user.studentsLimit} students · {user.roomsLimit} halls
        </span>
      ),
    },
    {
      key: 'lastSeenAt',
      title: 'Last seen',
      render: (user) => <span className="small muted nowrap">{moment(user.lastSeenAt)}</span>,
    },
    {
      key: 'actions',
      title: '',
      render: (user) => (
        <div className="row nowrap" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn--sm" onClick={() => setLimiting(user)}>
            <IconShield width={14} height={14} />
            Limits
          </button>
          <button type="button" className="btn btn--sm" onClick={() => setEditing(user)}>
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
              placeholder="Search by name, username or workspace"
              style={{ paddingLeft: 34 }}
            />
          </div>

          <select
            value={roleFilter}
            onChange={(event) => setRoleFilter(event.target.value)}
            style={{ width: 'auto', minWidth: 160 }}
          >
            {roles.map((role) => (
              <option key={role} value={role}>
                {role === 'ALL' ? 'Every role' : role.replace('_', ' ')}
              </option>
            ))}
          </select>

          <span className="toolbar__spacer" />
          <span className="muted small">
            {rows.length} of {users.length} accounts
          </span>
        </div>

        <Table
          columns={columns}
          rows={rows}
          empty="No account matches that search."
        />
      </section>

      {editing && (
        <ManageAccountDialog
          account={editing}
          busy={busy}
          onClose={() => setEditing(null)}
          onSave={(body) =>
            run(async () => {
              await adminApi.changeUser(editing.id, body)
              setEditing(null)
            })
          }
          onToggleStatus={() =>
            run(async () => {
              const next = editing.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'
              await adminApi.changeUser(editing.id, { status: next })
              setEditing(null)
            })
          }
        />
      )}

      {limiting && (
        <LimitsDialog
          account={limiting}
          plans={plans}
          busy={busy}
          onClose={() => setLimiting(null)}
          onSave={(limits) =>
            run(async () => {
              await adminApi.changeUserLimits(limiting.id, limits)
              setLimiting(null)
            })
          }
        />
      )}
    </div>
  )
}

/** The "manage an account" dialog: role, status, name, address, password. */
function ManageAccountDialog({ account, busy, onClose, onSave, onToggleStatus }) {
  const [role, setRole] = useState(account.role)
  const [fullName, setFullName] = useState(account.fullName || '')
  const [email, setEmail] = useState(account.email || '')
  const [password, setPassword] = useState('')

  return (
    <Modal
      title={`Manage ${account.username}`}
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
            {account.status === 'ACTIVE' ? 'Suspend' : 'Restore'}
          </button>
          <button
            type="button"
            className="btn btn--primary"
            disabled={busy}
            onClick={() =>
              onSave({
                role,
                fullName: fullName.trim(),
                email: email.trim(),
                password: password || undefined,
              })
            }
          >
            {busy ? 'Saving…' : 'Save'}
          </button>
        </>
      }
    >
      <div className="stack">
        <Field label="Role">
          <select value={role} onChange={(event) => setRole(event.target.value)}>
            <option value="ADMIN">ADMIN — runs the workspace</option>
            <option value="EXAM_CELL">EXAM_CELL — prepares the exam</option>
            <option value="INVIGILATOR">INVIGILATOR — reads the plan</option>
            <option value="PLATFORM_ADMIN">PLATFORM_ADMIN — the operator</option>
          </select>
        </Field>

        <Field label="Full name">
          <input
            type="text"
            value={fullName}
            onChange={(event) => setFullName(event.target.value)}
          />
        </Field>

        <Field label="Email address">
          <input
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        </Field>

        <Field
          label="Set a new password"
          hint="Leave it empty to keep the current one. The password itself is never shown — not here and not anywhere else."
        >
          <input
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="new-password"
            placeholder="At least 8 characters"
          />
        </Field>

        <Alert tone="info">
          <span className="row" style={{ gap: 8 }}>
            <IconKey width={15} height={15} />
            Passwords are stored hashed and cannot be read back — not by you, and
            not by anyone else.
          </span>
        </Alert>
      </div>
    </Modal>
  )
}

/** The "limits for this account" dialog. */
function LimitsDialog({ account, plans, busy, onClose, onSave }) {
  const [limits, setLimits] = useState({
    maxStudents: account.userOverrides?.maxStudents ?? '',
    maxRooms: account.userOverrides?.maxRooms ?? '',
    maxGenerationsPerMonth: account.userOverrides?.maxGenerationsPerMonth ?? '',
    storageLimitMb: account.userOverrides?.storageLimitMb ?? '',
    maxFileSizeMb: account.userOverrides?.maxFileSizeMb ?? '',
    seatsPerGeneration: account.userOverrides?.seatsPerGeneration ?? '',
  })

  const plan = plans.find((entry) => entry.code === account.planCode)
  const institution = account.institutionOverrides || {}

  function set(key, value) {
    setLimits((current) => ({ ...current, [key]: value }))
  }

  return (
    <Modal
      title={`Limits for ${account.username}`}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn--primary"
            disabled={busy}
            onClick={() => {
              const body = {}
              LIMIT_FIELDS.forEach(({ key }) => {
                const raw = limits[key]
                body[key] = raw === '' || raw === null ? null : Number(raw)
              })
              onSave(body)
            }}
          >
            {busy ? 'Saving…' : 'Save the limits'}
          </button>
        </>
      }
    >
      <div className="stack">
        <Alert tone="info">
          These limits apply to <b>{account.username}</b> alone. The workspace
          still works to the plan
          {plan ? ` (${plan.name})` : ''}
          {institution.maxStudents ? `, with the workspace's own limit of ${institution.maxStudents} students` : ''}
          . Leave a field empty to fall back to it.
        </Alert>

        {LIMIT_FIELDS.map((field) => {
          const planValue = plan ? plan[field.key] : undefined
          const institutionValue = institution[field.key]
          return (
            <Field
              key={field.key}
              label={`${field.label}${field.unit ? ` (${field.unit})` : ''}`}
              hint={
                planValue !== undefined
                  ? `The plan allows ${planValue}${field.unit ? ` ${field.unit}` : ''}${
                      institutionValue !== null && institutionValue !== undefined
                        ? `; the workspace is set to ${institutionValue}`
                        : ''
                    }.`
                  : undefined
              }
            >
              <input
                type="number"
                min="0"
                value={limits[field.key]}
                onChange={(event) => set(field.key, event.target.value)}
                placeholder={institutionValue ?? planValue ?? ''}
              />
            </Field>
          )
        })}
      </div>
    </Modal>
  )
}
