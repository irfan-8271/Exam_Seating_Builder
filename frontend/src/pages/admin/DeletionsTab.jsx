import { useCallback, useEffect, useState } from 'react'
import { Alert, Badge, Card, EmptyState, bytes, moment } from '../../components/ui'
import { adminApi } from '../../api/endpoints'

/**
 * Removing a college, or a single account, from the service.
 *
 * <p>Every removal is two steps. The preview says exactly what would go —
 * every kind of record, how much of it, and what is deliberately kept — and
 * the removal itself is a separate, confirmed act.</p>
 *
 * <p>Three things are protected and cannot be removed at all: the platform
 * institution, the last platform administrator, and the activity log that
 * records what was done.</p>
 */

/** Shows what a preview says, and offers the confirmation when it is allowed. */
function PreviewPanel({ preview, busy, onConfirm, confirmLabel }) {
  if (!preview) return null

  if (!preview.canDelete) {
    return (
      <Alert tone="error" title="This cannot be removed">
        <p style={{ margin: 0 }}>{preview.blockedReason}</p>
      </Alert>
    )
  }

  const removable = preview.categories.filter((c) => c.count > 0)

  return (
    <Alert tone="warning" title="Preview — nothing has been removed yet">
      <p style={{ margin: '0 0 8px' }}>
        Removing <strong>{preview.targetName}</strong> would remove{' '}
        <strong>{preview.totalRows}</strong> record{preview.totalRows === 1 ? '' : 's'}
        {preview.freedBytes > 0 && (
          <>
            , releasing <strong>{bytes(preview.freedBytes)}</strong> of uploaded content
          </>
        )}
        .
      </p>

      {removable.length > 0 && (
        <table className="table table--compact" style={{ marginBottom: 10 }}>
          <thead>
            <tr>
              <th>What</th>
              <th style={{ textAlign: 'right' }}>Records</th>
            </tr>
          </thead>
          <tbody>
            {removable.map((category) => (
              <tr key={category.key}>
                <td>
                  <strong>{category.label}</strong>
                  <div className="faint small">{category.note}</div>
                </td>
                <td style={{ textAlign: 'right' }}>{category.count}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {preview.retainedRows > 0 && (
        <p className="small muted" style={{ margin: '0 0 8px' }}>
          <strong>{preview.retainedRows}</strong> activity log entr
          {preview.retainedRows === 1 ? 'y is' : 'ies are'} kept. The record of what happened
          survives the thing it describes.
        </p>
      )}

      {preview.warnings?.length > 0 && (
        <ul className="small muted" style={{ margin: '0 0 10px', paddingLeft: 18 }}>
          {preview.warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}

      <button className="btn btn--sm btn--danger" onClick={onConfirm} disabled={busy}>
        {busy ? 'Removing…' : confirmLabel}
      </button>
    </Alert>
  )
}

/** One college that can be removed, with the preview of what would go. */
function InstitutionRow({ institution, busy, onPreview, onConfirm, preview, result }) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <tr>
        <td>
          <strong>{institution.name}</strong>
          <div className="faint small">{institution.slug}</div>
        </td>
        <td style={{ textAlign: 'right' }}>{institution.usersCount ?? 0}</td>
        <td style={{ textAlign: 'right' }}>{institution.studentsUsed ?? 0}</td>
        <td style={{ textAlign: 'right' }}>
          <button
            className="btn btn--sm btn--danger"
            onClick={() => {
              setOpen((value) => !value)
              onPreview(institution.id)
            }}
          >
            {open ? 'Close' : 'Remove…'}
          </button>
        </td>
      </tr>
      {open && (
        <tr>
          <td colSpan={4} style={{ background: 'var(--surface-2, #f8fafc)' }}>
            <PreviewPanel
              preview={preview}
              busy={busy}
              onConfirm={() => onConfirm(institution.id)}
              confirmLabel={`Yes, remove ${institution.name} and everything in it`}
            />
            {result && (
              <div style={{ marginTop: 10 }}>
                <Alert tone="success" title="Done">
                  <p style={{ margin: 0 }}>{result.message}</p>
                </Alert>
              </div>
            )}
          </td>
        </tr>
      )}
    </>
  )
}

/** One account that can be removed, with the preview of what would go. */
function UserRow({ account, busy, onPreview, onConfirm, preview, result }) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <tr>
        <td>
          <strong>{account.username}</strong>
          {account.fullName && <div className="faint small">{account.fullName}</div>}
        </td>
        <td>{account.role}</td>
        <td style={{ textAlign: 'right' }}>
          <button
            className="btn btn--sm btn--danger"
            onClick={() => {
              setOpen((value) => !value)
              onPreview(account.id)
            }}
          >
            {open ? 'Close' : 'Remove…'}
          </button>
        </td>
      </tr>
      {open && (
        <tr>
          <td colSpan={3} style={{ background: 'var(--surface-2, #f8fafc)' }}>
            <PreviewPanel
              preview={preview}
              busy={busy}
              onConfirm={() => onConfirm(account.id)}
              confirmLabel={`Yes, remove the account ${account.username}`}
            />
            {result && (
              <div style={{ marginTop: 10 }}>
                <Alert tone="success" title="Done">
                  <p style={{ margin: 0 }}>{result.message}</p>
                </Alert>
              </div>
            )}
          </td>
        </tr>
      )}
    </>
  )
}

export default function DeletionsTab() {
  const [institutions, setInstitutions] = useState([])
  const [users, setUsers] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const [institutionPreview, setInstitutionPreview] = useState(null)
  const [userPreview, setUserPreview] = useState(null)
  const [institutionResult, setInstitutionResult] = useState(null)
  const [userResult, setUserResult] = useState(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      const [institutionList, userList] = await Promise.all([
        adminApi.institutions(),
        adminApi.users(),
      ])
      setInstitutions(institutionList || [])
      setUsers(userList || [])
    } catch (failure) {
      setError(failure.message || 'Could not read the accounts and institutions.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const withAction = useCallback(
    async (work, after) => {
      setBusy(true)
      setError(null)
      try {
        const result = await work()
        after(result)
        await load()
      } catch (failure) {
        setError(failure.message || 'The removal could not be completed.')
      } finally {
        setBusy(false)
      }
    },
    [load],
  )

  const previewInstitution = useCallback(
    (id) =>
      withAction(
        () => adminApi.previewInstitutionDeletion(id),
        (result) => {
          setInstitutionPreview(result)
          setInstitutionResult(null)
        },
      ),
    [withAction],
  )

  const previewUser = useCallback(
    (id) =>
      withAction(
        () => adminApi.previewUserDeletion(id),
        (result) => {
          setUserPreview(result)
          setUserResult(null)
        },
      ),
    [withAction],
  )

  const removeInstitution = useCallback(
    (id) =>
      withAction(
        () => adminApi.deleteInstitution({ institutionId: id, confirm: true }),
        (result) => {
          setInstitutionResult(result)
          setInstitutionPreview(null)
        },
      ),
    [withAction],
  )

  const removeUser = useCallback(
    (id) =>
      withAction(
        () => adminApi.deleteUser({ userId: id, confirm: true }),
        (result) => {
          setUserResult(result)
          setUserPreview(null)
        },
      ),
    [withAction],
  )

  if (loading) {
    return <p className="muted">Reading the accounts and institutions…</p>
  }

  return (
    <div className="stack">
      {error && (
        <Alert tone="error" title="Something went wrong">
          <p style={{ margin: 0 }}>{error}</p>
        </Alert>
      )}

      <Alert tone="info" title="Before anything is removed">
        <p style={{ margin: 0 }}>
          Removing a college takes its students, halls, seat allocations, seating rounds, uploaded
          files, subscription and upgrade requests with it. Its users lose access at once and are
          not told why. The activity log is kept — the record of what happened outlives the thing
          it describes.
        </p>
      </Alert>

      <Card
        title="Registered institutions"
        subtitle="Remove a college and everything belonging to it."
      >
        {institutions.length === 0 ? (
          <EmptyState title="No institutions to remove" />
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Institution</th>
                <th style={{ textAlign: 'right' }}>Users</th>
                <th style={{ textAlign: 'right' }}>Students</th>
                <th style={{ textAlign: 'right' }} />
              </tr>
            </thead>
            <tbody>
              {institutions.map((institution) => (
                <InstitutionRow
                  key={institution.id}
                  institution={institution}
                  busy={busy}
                  onPreview={previewInstitution}
                  onConfirm={removeInstitution}
                  preview={institutionPreview}
                  result={institutionResult}
                />
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title="User accounts" subtitle="Remove one account and leave its college alone.">
        {users.length === 0 ? (
          <EmptyState title="No accounts to remove" />
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Account</th>
                <th>Role</th>
                <th style={{ textAlign: 'right' }} />
              </tr>
            </thead>
            <tbody>
              {users.map((account) => (
                <UserRow
                  key={account.id}
                  account={account}
                  busy={busy}
                  onPreview={previewUser}
                  onConfirm={removeUser}
                  preview={userPreview}
                  result={userResult}
                />
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  )
}
