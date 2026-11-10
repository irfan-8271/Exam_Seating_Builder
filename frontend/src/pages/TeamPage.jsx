import { useCallback, useEffect, useState } from 'react'
import { memberApi } from '../api/endpoints'
import { useAuth } from '../auth/AuthContext'
import { Alert, Badge, Card, EmptyState, Field, Loading, Modal, Table, moment } from '../components/ui'
import { IconTrash, IconUser } from '../components/Icons'

const ROLES = [
  {
    code: 'ADMIN',
    name: 'Administrator',
    about: 'Runs the workspace: the roster, the halls, the plans and the accounts.',
  },
  {
    code: 'EXAM_CELL',
    name: 'Exam cell',
    about: 'Prepares the exam — the roster, the halls and the seating plans.',
  },
  {
    code: 'INVIGILATOR',
    name: 'Invigilator',
    about: 'Reads the plan and the door notices. Cannot change anything.',
  },
]

/**
 * The people who share this workspace.
 *
 * <p>The person who created the account is its administrator and may give
 * their colleagues their own login. A password is set here and never shown
 * again — it is stored hashed and cannot be read back.</p>
 */
export default function TeamPage() {
  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN'

  const [members, setMembers] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const [busy, setBusy] = useState(false)
  const [adding, setAdding] = useState(false)
  const [editing, setEditing] = useState(null)
  const [removing, setRemoving] = useState(null)

  const load = useCallback(async () => {
    try {
      setMembers(await memberApi.list())
    } catch (failure) {
      setError(failure.message || 'The list of members could not be loaded')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function run(action, message) {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      await action()
      await load()
      if (message) setNotice(message)
    } catch (failure) {
      setError(failure.message || 'The change could not be saved')
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <Loading label="Loading your team…" />

  const columns = [
    {
      key: 'username',
      title: 'Person',
      render: (member) => (
        <div className="user-cell">
          <span className="avatar">
            {(member.fullName || member.username || '?').slice(0, 2).toUpperCase()}
          </span>
          <div style={{ minWidth: 0 }}>
            <div className="user-cell__name">
              {member.fullName || member.username}
              {member.id === user?.id && (
                <span className="faint" style={{ fontWeight: 500 }}>
                  {' '}
                  (you)
                </span>
              )}
            </div>
            <div className="user-cell__meta">
              {member.username}
              {member.email ? ` · ${member.email}` : ''}
            </div>
          </div>
        </div>
      ),
    },
    {
      key: 'role',
      title: 'Role',
      render: (member) => (
        <Badge tone={member.role === 'ADMIN' ? 'brand' : 'default'}>
          {ROLES.find((role) => role.code === member.role)?.name || member.role}
        </Badge>
      ),
    },
    {
      key: 'status',
      title: 'Status',
      render: (member) => (
        <Badge tone={member.status === 'ACTIVE' ? 'ok' : 'bad'}>{member.status}</Badge>
      ),
    },
    {
      key: 'lastSeenAt',
      title: 'Last seen',
      render: (member) => <span className="small muted nowrap">{moment(member.lastSeenAt)}</span>,
    },
    ...(isAdmin
      ? [
          {
            key: 'actions',
            title: '',
            render: (member) => (
              <div className="row row--end">
                <button
                  type="button"
                  className="btn btn--sm"
                  onClick={() => setEditing(member)}
                >
                  Manage
                </button>
                {member.id !== user?.id && (
                  <button
                    type="button"
                    className="icon-button"
                    onClick={() => setRemoving(member)}
                    aria-label={`Remove ${member.username}`}
                  >
                    <IconTrash width={16} height={16} />
                  </button>
                )}
              </div>
            ),
          },
        ]
      : []),
  ]

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Team</h1>
          <p>
            {members.length} {members.length === 1 ? 'person has' : 'people have'} access
            to this workspace.
          </p>
        </div>
        {isAdmin && (
          <button type="button" className="btn btn--primary" onClick={() => setAdding(true)}>
            Add a person
          </button>
        )}
      </div>

      {error && <Alert tone="error">{error}</Alert>}
      {notice && <Alert tone="success">{notice}</Alert>}

      <Card flush>
        <Table columns={columns} rows={members} empty="Nobody else has an account yet." />
      </Card>

      {!isAdmin && (
        <p className="small faint">
          Only the administrator of this workspace can add or change people.
        </p>
      )}

      {adding && (
        <AddMemberDialog
          busy={busy}
          onClose={() => setAdding(false)}
          onSave={(body) =>
            run(async () => {
              await memberApi.create(body)
              setAdding(false)
            }, `${body.username} can now sign in.`)
          }
        />
      )}

      {editing && (
        <ManageMemberDialog
          member={editing}
          busy={busy}
          isSelf={editing.id === user?.id}
          onClose={() => setEditing(null)}
          onSave={(body) =>
            run(async () => {
              await memberApi.update(editing.id, body)
              setEditing(null)
            }, 'The account was updated.')
          }
        />
      )}

      {removing && (
        <Modal
          title={`Remove ${removing.username}?`}
          onClose={() => setRemoving(null)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setRemoving(null)}>
                Keep the account
              </button>
              <button
                type="button"
                className="btn btn--danger"
                disabled={busy}
                onClick={() =>
                  run(async () => {
                    await memberApi.remove(removing.id)
                    setRemoving(null)
                  }, `${removing.username} no longer has access.`)
                }
              >
                Remove
              </button>
            </>
          }
        >
          <p>
            {removing.username} will no longer be able to sign in to this
            workspace. The students, halls and seating plans they created stay
            exactly where they are.
          </p>
        </Modal>
      )}
    </>
  )
}

/** The "add a person" dialog. */
function AddMemberDialog({ busy, onClose, onSave }) {
  const [form, setForm] = useState({
    username: '',
    password: '',
    role: 'EXAM_CELL',
    fullName: '',
    email: '',
  })

  const selected = ROLES.find((role) => role.code === form.role)

  return (
    <Modal
      title="Add a person"
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
            onClick={() => onSave({ ...form, username: form.username.trim() })}
          >
            {busy ? 'Adding…' : 'Add them'}
          </button>
        </>
      }
    >
      <div className="stack">
        <Field label="Username" hint="What they will type to sign in.">
          <input
            type="text"
            value={form.username}
            onChange={(event) => setForm({ ...form, username: event.target.value })}
            placeholder="r.mehta"
            autoComplete="off"
            required
          />
        </Field>

        <Field
          label="Password"
          hint="At least 8 characters. Give it to them directly — it cannot be shown again."
        >
          <input
            type="password"
            value={form.password}
            onChange={(event) => setForm({ ...form, password: event.target.value })}
            autoComplete="new-password"
            required
            minLength={8}
          />
        </Field>

        <Field label="Role" hint={selected?.about}>
          <select
            value={form.role}
            onChange={(event) => setForm({ ...form, role: event.target.value })}
          >
            {ROLES.map((role) => (
              <option key={role.code} value={role.code}>
                {role.name}
              </option>
            ))}
          </select>
        </Field>

        <Field label="Their name">
          <input
            type="text"
            value={form.fullName}
            onChange={(event) => setForm({ ...form, fullName: event.target.value })}
            placeholder="R. Mehta"
          />
        </Field>

        <Field label="Email address">
          <input
            type="email"
            value={form.email}
            onChange={(event) => setForm({ ...form, email: event.target.value })}
            placeholder="r.mehta@example.org"
          />
        </Field>
      </div>
    </Modal>
  )
}

/** The "manage a person" dialog. */
function ManageMemberDialog({ member, busy, isSelf, onClose, onSave }) {
  const [role, setRole] = useState(member.role)
  const [status, setStatus] = useState(member.status)
  const [fullName, setFullName] = useState(member.fullName || '')
  const [email, setEmail] = useState(member.email || '')
  const [password, setPassword] = useState('')

  return (
    <Modal
      title={`Manage ${member.username}`}
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
            onClick={() =>
              onSave({
                role,
                status,
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
        {isSelf && (
          <Alert tone="info">
            This is your own account. You cannot change your own role — ask
            another administrator if it needs to change.
          </Alert>
        )}

        <Field label="Role">
          <select
            value={role}
            onChange={(event) => setRole(event.target.value)}
            disabled={isSelf}
          >
            {ROLES.map((entry) => (
              <option key={entry.code} value={entry.code}>
                {entry.name}
              </option>
            ))}
          </select>
        </Field>

        <Field label="Status" hint="A suspended account cannot sign in.">
          <select
            value={status}
            onChange={(event) => setStatus(event.target.value)}
            disabled={isSelf}
          >
            <option value="ACTIVE">Active</option>
            <option value="SUSPENDED">Suspended</option>
          </select>
        </Field>

        <Field label="Their name">
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
      </div>
    </Modal>
  )
}
