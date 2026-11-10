import { useCallback, useEffect, useState } from 'react'
import { adminApi } from '../api/endpoints'
import { Alert, Badge, Card, EmptyState, Field, Loading, Modal, Stat, Table, bytes, moment } from '../components/ui'

/**
 * The platform administration screen.
 *
 * The operator sees the whole service: every institution, every account, how
 * much each of them generates, and may raise or lower the limits they work
 * under — because a subscription limit that blocks a customer is a problem
 * only the operator can solve.
 *
 * A limit is either the plan's number or a custom override; both are set
 * here, and an override that is left empty falls back to the plan.
 */
export default function AdminPage() {
  const [tab, setTab] = useState('institutions')
  const [institutions, setInstitutions] = useState([])
  const [users, setUsers] = useState([])
  const [usage, setUsage] = useState(null)
  const [generations, setGenerations] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const [limitsFor, setLimitsFor] = useState(null)
  const [statusFor, setStatusFor] = useState(null)
  const [userFor, setUserFor] = useState(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [institutionList, userList, usageData, generationList] = await Promise.all([
        adminApi.institutions(),
        adminApi.users(),
        adminApi.usage(),
        adminApi.generations(50),
      ])
      setInstitutions(institutionList || [])
      setUsers(userList || [])
      setUsage(usageData)
      setGenerations(generationList || [])
    } catch (failure) {
      setError(failure.message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  if (loading) return <Loading label="Loading the platform view…" />

  return (
    <>
      <header className="topbar">
        <div className="topbar__title">
          <h1>Platform administration</h1>
          <p>
            {institutions.length} institution{institutions.length === 1 ? '' : 's'} ·{' '}
            {users.length} account{users.length === 1 ? '' : 's'}
          </p>
        </div>
        <button type="button" className="btn" onClick={load}>
          Refresh
        </button>
      </header>

      <div className="content">
        {error && <Alert tone="error">{error}</Alert>}
        {notice && <Alert tone="success">{notice}</Alert>}

        {usage && (
          <div className="grid grid--4">
            <Stat label="Institutions" value={usage.institutions} />
            <Stat label="Accounts" value={usage.users} />
            <Stat label="Students" value={usage.students} hint="across every institution" />
            <Stat
              label="Generations"
              value={usage.generations}
              hint={`${usage.seats} seats produced`}
            />
            <Stat
              label="Storage"
              value={bytes(usage.storageUsedBytes)}
              hint="of uploaded files"
            />
            <Stat
              label="Halls"
              value={usage.halls}
              hint={`${usage.generations} plans generated`}
            />
            {usage.byPlan?.map((plan) => (
              <Stat
                key={plan.planCode}
                label={plan.planName || plan.planCode}
                value={plan.count}
                hint="institutions on this plan"
              />
            ))}
          </div>
        )}

        <div className="auth__tabs" style={{ maxWidth: 420, marginTop: 18 }}>
          <button
            type="button"
            className={`auth__tab ${tab === 'institutions' ? 'auth__tab--active' : ''}`}
            onClick={() => setTab('institutions')}
          >
            Institutions
          </button>
          <button
            type="button"
            className={`auth__tab ${tab === 'users' ? 'auth__tab--active' : ''}`}
            onClick={() => setTab('users')}
          >
            Accounts
          </button>
          <button
            type="button"
            className={`auth__tab ${tab === 'activity' ? 'auth__tab--active' : ''}`}
            onClick={() => setTab('activity')}
          >
            Activity
          </button>
        </div>

        {tab === 'institutions' && (
          <Card flush>
            {institutions.length === 0 ? (
              <EmptyState title="No institutions yet" />
            ) : (
              <Table
                rows={institutions}
                columns={[
                  {
                    key: 'name',
                    title: 'Institution',
                    render: (row) => (
                      <span>
                        <strong>{row.name}</strong>
                        <div className="faint small">{row.slug}</div>
                      </span>
                    ),
                  },
                  {
                    key: 'status',
                    title: 'Status',
                    render: (row) => (
                      <Badge tone={row.status === 'ACTIVE' ? 'success' : 'danger'}>
                        {row.status}
                      </Badge>
                    ),
                  },
                  {
                    key: 'plan',
                    title: 'Plan',
                    render: (row) => (
                      <span>
                        <strong>{row.planCode}</strong>
                        {row.planName && <div className="faint small">{row.planName}</div>}
                      </span>
                    ),
                  },
                  {
                    key: 'students',
                    title: 'Students',
                    numeric: true,
                    render: (row) => (
                      <span>
                        {row.studentsUsed}
                        <span className="faint"> / {row.studentsLimit}</span>
                        {row.overrides?.maxStudents != null && (
                          <div className="faint small">override {row.overrides.maxStudents}</div>
                        )}
                      </span>
                    ),
                  },
                  {
                    key: 'generations',
                    title: 'Generations',
                    numeric: true,
                    render: (row) => (
                      <span>
                        {row.generationsUsed}
                        <span className="faint"> / {row.generationsLimit}</span>
                      </span>
                    ),
                  },
                  {
                    key: 'storage',
                    title: 'Storage',
                    numeric: true,
                    render: (row) => (
                      <span>
                        {bytes(row.storageUsedBytes)}
                        <span className="faint"> / {bytes(row.storageLimitBytes)}</span>
                      </span>
                    ),
                  },
                  {
                    key: 'users',
                    title: 'Users',
                    numeric: true,
                    render: (row) => row.usersCount,
                  },
                  {
                    key: 'actions',
                    title: '',
                    render: (row) => (
                      <div className="btn-row">
                        <button
                          type="button"
                          className="btn btn--sm btn--primary"
                          onClick={() => setLimitsFor(row)}
                        >
                          Limits
                        </button>
                        <button
                          type="button"
                          className="btn btn--sm"
                          onClick={() => setStatusFor(row)}
                        >
                          {row.status === 'ACTIVE' ? 'Suspend' : 'Activate'}
                        </button>
                      </div>
                    ),
                  },
                ]}
              />
            )}
          </Card>
        )}

        {tab === 'users' && (
          <Card flush>
            {users.length === 0 ? (
              <EmptyState title="No accounts yet" />
            ) : (
              <Table
                rows={users}
                columns={[
                  {
                    key: 'username',
                    title: 'Account',
                    render: (row) => (
                      <span>
                        <strong>{row.username}</strong>
                        {row.fullName && <div className="faint small">{row.fullName}</div>}
                      </span>
                    ),
                  },
                  {
                    key: 'role',
                    title: 'Role',
                    render: (row) => (
                      <Badge tone={row.role === 'PLATFORM_ADMIN' ? 'primary' : 'default'}>
                        {row.role}
                      </Badge>
                    ),
                  },
                  {
                    key: 'institutionName',
                    title: 'Institution',
                    render: (row) => row.institutionName || <span className="faint">—</span>,
                  },
                  {
                    key: 'lastSeenAt',
                    title: 'Last seen',
                    render: (row) =>
                      row.lastSeenAt ? (
                        <span className="small">{moment(row.lastSeenAt)}</span>
                      ) : (
                        <span className="faint small">never</span>
                      ),
                  },
                  {
                    key: 'actions',
                    title: '',
                    render: (row) => (
                      <button
                        type="button"
                        className="btn btn--sm"
                        onClick={() => setUserFor(row)}
                      >
                        Edit
                      </button>
                    ),
                  },
                ]}
              />
            )}
          </Card>
        )}

        {tab === 'activity' && (
          <Card title="Recent generations across the service" flush>
            {generations.length === 0 ? (
              <EmptyState title="Nothing generated yet" />
            ) : (
              <Table
                rows={generations}
                columns={[
                  { key: 'institutionName', title: 'Institution' },
                  {
                    key: 'subjectCode',
                    title: 'Subject',
                    render: (row) => row.subjectCode || 'All subjects',
                  },
                  { key: 'seats', title: 'Seats', numeric: true },
                  { key: 'planCode', title: 'Plan' },
                  {
                    key: 'createdAt',
                    title: 'When',
                    render: (row) => <span className="small">{moment(row.createdAt)}</span>,
                  },
                ]}
              />
            )}
          </Card>
        )}
      </div>

      {limitsFor && (
        <LimitsDialog
          institution={limitsFor}
          onClose={() => setLimitsFor(null)}
          onSaved={(message) => {
            setLimitsFor(null)
            setNotice(message)
            load()
          }}
        />
      )}

      {statusFor && (
        <StatusDialog
          institution={statusFor}
          onClose={() => setStatusFor(null)}
          onSaved={(message) => {
            setStatusFor(null)
            setNotice(message)
            load()
          }}
        />
      )}

      {userFor && (
        <UserDialog
          user={userFor}
          institutions={institutions}
          onClose={() => setUserFor(null)}
          onSaved={(message) => {
            setUserFor(null)
            setNotice(message)
            load()
          }}
        />
      )}
    </>
  )
}

/**
 * The limits of one institution — the plan and the custom overrides.
 *
 * An empty override means "use whatever the plan says". A number means "this
 * institution may go that far, whatever the plan says".
 */
function LimitsDialog({ institution, onClose, onSaved }) {
  const [planCode, setPlanCode] = useState(institution.planCode)
  const [overrides, setOverrides] = useState(() => ({
    maxStudents: institution.overrides?.maxStudents ?? '',
    maxRooms: institution.overrides?.maxRooms ?? '',
    maxGenerationsPerMonth: institution.overrides?.maxGenerationsPerMonth ?? '',
    storageLimitMb: institution.overrides?.storageLimitMb ?? '',
    maxFileSizeMb: institution.overrides?.maxFileSizeMb ?? '',
    seatsPerGeneration: institution.overrides?.seatsPerGeneration ?? '',
  }))
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const FIELDS = [
    { key: 'maxStudents', label: 'Students' },
    { key: 'maxRooms', label: 'Halls' },
    { key: 'maxGenerationsPerMonth', label: 'Generations per month' },
    { key: 'storageLimitMb', label: 'Storage (MB)' },
    { key: 'maxFileSizeMb', label: 'Maximum file size (MB)' },
    { key: 'seatsPerGeneration', label: 'Seats per generation' },
  ]

  async function submit() {
    setError(null)
    setBusy(true)
    try {
      const limits = {}
      FIELDS.forEach((field) => {
        const raw = overrides[field.key]
        limits[field.key] = raw === '' || raw === null ? null : Number(raw)
      })
      await adminApi.changeSubscription(institution.id, { planCode, limits })
      onSaved(`The limits of ${institution.name} were changed.`)
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title={`Limits — ${institution.name}`}
      wide
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="button" className="btn btn--primary" onClick={submit} disabled={busy}>
            {busy && <span className="spinner" />}
            Save the limits
          </button>
        </>
      }
    >
      {error && <Alert tone="error">{error}</Alert>}

      <Field label="Plan" hint="The plan the institution is on.">
        <select value={planCode} onChange={(event) => setPlanCode(event.target.value)}>
          {['FREE', 'STARTER', 'PRO', 'ENTERPRISE'].map((code) => (
            <option key={code} value={code}>
              {code}
            </option>
          ))}
        </select>
      </Field>

      <fieldset>
        <legend>Custom overrides</legend>
        <p className="small muted">
          Leave a field empty to follow the plan. Fill it in to let this institution go
          beyond it — that is the way to unblock a customer whose plan is too small.
        </p>
        <div className="grid grid--2">
          {FIELDS.map((field) => (
            <Field key={field.key} label={field.label}>
              <input
                type="number"
                min="0"
                value={overrides[field.key]}
                placeholder="follow the plan"
                onChange={(event) =>
                  setOverrides({ ...overrides, [field.key]: event.target.value })
                }
              />
            </Field>
          ))}
        </div>
      </fieldset>

      <div className="small muted">
        Currently: {institution.studentsUsed}/{institution.studentsLimit} students ·{' '}
        {institution.roomsUsed}/{institution.roomsLimit} halls ·{' '}
        {institution.generationsUsed}/{institution.generationsLimit} generations ·{' '}
        {bytes(institution.storageUsedBytes)}/{bytes(institution.storageLimitBytes)} storage
      </div>
    </Modal>
  )
}

function StatusDialog({ institution, onClose, onSaved }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const suspending = institution.status === 'ACTIVE'

  async function submit() {
    setBusy(true)
    setError(null)
    try {
      await adminApi.changeStatus(institution.id, suspending ? 'SUSPENDED' : 'ACTIVE')
      onSaved(
        `${institution.name} was ${suspending ? 'suspended' : 'activated'}.`,
      )
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title={suspending ? 'Suspend institution' : 'Activate institution'}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className={suspending ? 'btn btn--danger' : 'btn btn--primary'}
            onClick={submit}
            disabled={busy}
          >
            {busy && <span className="spinner" />}
            {suspending ? 'Suspend' : 'Activate'}
          </button>
        </>
      }
    >
      {error && <Alert tone="error">{error}</Alert>}
      <p>
        {suspending ? (
          <>
            Suspending <strong>{institution.name}</strong> stops its accounts from signing
            in. The roster, the halls and the plans stay untouched.
          </>
        ) : (
          <>
            Activating <strong>{institution.name}</strong> lets its accounts sign in again.
          </>
        )}
      </p>
    </Modal>
  )
}

function UserDialog({ user, institutions, onClose, onSaved }) {
  const [role, setRole] = useState(user.role)
  const [institutionId, setInstitutionId] = useState(user.institutionId || '')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function submit() {
    setError(null)
    setBusy(true)
    try {
      const body = { role }
      if (institutionId) body.institutionId = Number(institutionId)
      if (password.trim()) body.password = password
      await adminApi.changeUser(user.id, body)
      onSaved(`${user.username} was updated.`)
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title={`Edit ${user.username}`}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="button" className="btn btn--primary" onClick={submit} disabled={busy}>
            {busy && <span className="spinner" />}
            Save
          </button>
        </>
      }
    >
      {error && <Alert tone="error">{error}</Alert>}
      <Field label="Role">
        <select value={role} onChange={(event) => setRole(event.target.value)}>
          <option value="ADMIN">ADMIN</option>
          <option value="EXAM_CELL">EXAM_CELL</option>
          <option value="INVIGILATOR">INVIGILATOR</option>
          <option value="PLATFORM_ADMIN">PLATFORM_ADMIN</option>
        </select>
      </Field>
      <Field label="Institution">
        <select
          value={institutionId}
          onChange={(event) => setInstitutionId(event.target.value)}
        >
          <option value="">— none —</option>
          {institutions.map((institution) => (
            <option key={institution.id} value={institution.id}>
              {institution.name}
            </option>
          ))}
        </select>
      </Field>
      <Field label="New password" hint="Leave empty to keep the current one. At least 8 characters.">
        <input
          type="password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="new-password"
        />
      </Field>
    </Modal>
  )
}
