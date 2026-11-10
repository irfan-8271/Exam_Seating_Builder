import { Badge, bytes, moment } from '../../components/ui'
import { IconActivity, IconBuilding, IconChart, IconUsers } from '../../components/Icons'

/** The numbers the operator looks at first. */
function Kpi({ label, value, hint, icon }) {
  return (
    <div className="kpi">
      <div className="kpi__label">
        {label}
        <span style={{ color: 'var(--brand-400)', display: 'inline-flex' }}>{icon}</span>
      </div>
      <div className="kpi__value">{value}</div>
      {hint && <div className="kpi__hint">{hint}</div>}
    </div>
  )
}

/** The feed, shortened to a handful of entries. */
function RecentActivity({ activity }) {
  if (!activity || activity.length === 0) {
    return <p className="muted small">Nothing has happened yet.</p>
  }
  return (
    <div>
      {activity.slice(0, 8).map((entry) => (
        <div className="activity" key={entry.id}>
          <span className={`activity__dot activity__dot--${entry.type.toLowerCase()}`} />
          <div className="activity__body">
            <div className="activity__title">
              <b>{entry.username || 'Someone'}</b>{' '}
              {entry.type === 'REGISTRATION' && 'created an account'}
              {entry.type === 'SIGN_IN' && 'signed in'}
              {entry.type === 'SIGN_OUT' && 'signed out'}
              {entry.type === 'GENERATION' && 'generated a seating plan'}
              {entry.type === 'PLAN_CHANGE' && 'changed the plan'}
              {entry.type === 'LIMIT_CHANGE' && 'changed the limits'}
              {entry.type === 'USER_CHANGE' && 'changed an account'}
              {entry.type === 'ACCOUNT_SUSPENDED' && 'suspended a workspace'}
              {entry.type === 'ACCOUNT_RESTORED' && 'restored a workspace'}
            </div>
            {entry.detail && <div className="activity__detail">{entry.detail}</div>}
          </div>
          <span className="activity__time">{moment(entry.occurredAt)}</span>
        </div>
      ))}
    </div>
  )
}

/**
 * The overview: the service in one screen.
 */
export default function OverviewTab({ usage, activity, users, institutions }) {
  const recent = (users || []).slice(0, 5)
  const suspendedUsers = (users || []).filter((user) => user.status === 'SUSPENDED').length
  const suspendedWorkspaces = (institutions || []).filter(
    (institution) => institution.status === 'SUSPENDED',
  ).length

  return (
    <div className="stack stack--loose">
      <div className="grid grid--4">
        <Kpi
          label="Workspaces"
          value={usage?.institutions ?? 0}
          hint={`${suspendedWorkspaces} suspended`}
          icon={<IconBuilding width={16} height={16} />}
        />
        <Kpi
          label="Accounts"
          value={usage?.users ?? 0}
          hint={`${suspendedUsers} suspended`}
          icon={<IconUsers width={16} height={16} />}
        />
        <Kpi
          label="Students"
          value={(usage?.students ?? 0).toLocaleString()}
          hint={`${(usage?.halls ?? 0).toLocaleString()} halls across the service`}
          icon={<IconChart width={16} height={16} />}
        />
        <Kpi
          label="Plans generated"
          value={(usage?.generations ?? 0).toLocaleString()}
          hint={`${(usage?.seats ?? 0).toLocaleString()} seats seated this period`}
          icon={<IconActivity width={16} height={16} />}
        />
      </div>

      <div className="grid grid--2">
        <section className="card">
          <header className="card__head">
            <h2>How the workspaces are spread</h2>
          </header>
          <div className="card__body">
            {usage?.byPlan?.length ? (
              <div className="stack stack--tight">
                {usage.byPlan.map((entry) => (
                  <div className="spread" key={entry.planCode}>
                    <span className="strong">{entry.planName}</span>
                    <Badge tone="brand">
                      {entry.count} {entry.count === 1 ? 'workspace' : 'workspaces'}
                    </Badge>
                  </div>
                ))}
              </div>
            ) : (
              <p className="muted small">No workspaces yet.</p>
            )}
            <div className="spread" style={{ marginTop: 16, paddingTop: 12, borderTop: '1px solid var(--border)' }}>
              <span className="muted small">Storage in use</span>
              <span className="strong">{bytes(usage?.storageUsedBytes ?? 0)}</span>
            </div>
          </div>
        </section>

        <section className="card">
          <header className="card__head">
            <h2>The newest accounts</h2>
          </header>
          <div className="card__body">
            {recent.length === 0 ? (
              <p className="muted small">Nobody has registered yet.</p>
            ) : (
              <div className="stack stack--tight">
                {recent.map((account) => (
                  <div className="user-cell" key={account.id}>
                    <span className="avatar">{initials(account)}</span>
                    <div style={{ minWidth: 0 }}>
                      <div className="user-cell__name">{account.username}</div>
                      <div className="user-cell__meta">
                        {account.fullName || account.email || 'No name given'} ·{' '}
                        {account.institutionName || 'No workspace'}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </section>
      </div>

      <section className="card">
        <header className="card__head">
          <h2>What has just happened</h2>
        </header>
        <div className="card__body card__body--flush">
          <RecentActivity activity={activity} />
        </div>
      </section>
    </div>
  )
}

/** The first letters of a name, for the little circle in the table. */
export function initials(account) {
  const source = (account.fullName || account.username || '?').trim()
  const parts = source.split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}
