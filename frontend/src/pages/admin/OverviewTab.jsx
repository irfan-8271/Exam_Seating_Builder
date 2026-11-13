import { Alert, Badge, Stat, StatusBadge, bytes, formatMoney, moment } from '../../components/ui'
import {
  IconActivity,
  IconBuilding,
  IconChart,
  IconCreditCard,
  IconUsers,
} from '../../components/Icons'

const ACTIVITY_LABELS = {
  REGISTRATION: 'registered a new institution workspace',
  SIGN_IN: 'signed in',
  SIGN_OUT: 'signed out',
  GENERATION: 'generated a seating plan',
  PLAN_CHANGE: 'updated subscription plan',
  LIMIT_CHANGE: 'adjusted custom capacity limits',
  USER_CHANGE: 'updated account settings',
  ACCOUNT_SUSPENDED: 'suspended a workspace',
  ACCOUNT_RESTORED: 'restored a workspace',
  UPGRADE_REQUESTED: 'submitted a subscription upgrade request',
  UPGRADE_APPROVED: 'approved a subscription upgrade request',
  UPGRADE_REJECTED: 'rejected a subscription upgrade request',
  PAYMENT_CONFIG_UPDATED: 'updated manual payment verification settings',
  PLAN_CATALOG_UPDATED: 'updated global subscription tier catalog',
}

function RecentActivity({ activity }) {
  if (!activity || activity.length === 0) {
    return <p className="muted small">No platform activity recorded yet.</p>
  }
  return (
    <div>
      {activity.slice(0, 8).map((entry) => (
        <div className="activity" key={entry.id}>
          <span className={`activity__dot activity__dot--${entry.type.toLowerCase()}`} />
          <div className="activity__body">
            <div className="activity__title">
              <b>{entry.username || entry.institutionName || 'System'}</b>{' '}
              {ACTIVITY_LABELS[entry.type] || entry.type.toLowerCase()}
            </div>
            {entry.detail && <div className="activity__detail">{entry.detail}</div>}
          </div>
          <span className="activity__time">{moment(entry.occurredAt)}</span>
        </div>
      ))}
    </div>
  )
}

export function initials(value) {
  if (!value) return '?'
  const parts = String(value).trim().split(/\s+/)
  if (parts.length >= 2) {
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
  }
  return String(value).slice(0, 2).toUpperCase()
}

export default function OverviewTab({
  usage,
  activity,
  users = [],
  institutions = [],
  requests = [],
  paymentConfig,
  onNavigate,
}) {
  const currencySymbol = paymentConfig?.currencySymbol || '₹'
  const currencyCode = paymentConfig?.currencyCode || 'INR'
  const suspendedUsers = users.filter((u) => u.status === 'SUSPENDED').length
  const suspendedWorkspaces = institutions.filter((i) => i.status === 'SUSPENDED').length
  const pendingRequests = requests.filter((r) => r.status === 'PENDING')
  const paidInstitutions = institutions.filter(
    (i) => i.planCode && i.planCode !== 'FREE' && i.status === 'ACTIVE',
  ).length

  return (
    <div className="stack stack--loose">
      {pendingRequests.length > 0 && (
        <Alert
          tone="warning"
          title={`${pendingRequests.length} Subscription Upgrade Request${
            pendingRequests.length === 1 ? '' : 's'
          } Awaiting Payment Verification`}
          actions={
            onNavigate && (
              <button
                type="button"
                className="btn btn--sm btn--primary"
                onClick={() => onNavigate('requests')}
              >
                Review Queue
              </button>
            )
          }
        >
          Verify submitted UTR numbers and payment screenshots before activating plan or
          capacity changes.
        </Alert>
      )}

      <div className="grid grid--4">
        <Stat
          label="Registered Institutions"
          value={usage?.institutions ?? institutions.length}
          accent="brand"
          hint={`${paidInstitutions} paid · ${suspendedWorkspaces} suspended`}
          icon={<IconBuilding width={18} height={18} />}
        />
        <Stat
          label="Pending Verifications"
          value={usage?.pendingRequests ?? pendingRequests.length}
          accent="amber"
          hint={`${usage?.approvedRequests ?? 0} approved · ${usage?.rejectedRequests ?? 0} rejected`}
          icon={<IconCreditCard width={18} height={18} />}
        />
        <Stat
          label="Students Managed"
          value={(usage?.students ?? 0).toLocaleString()}
          accent="emerald"
          hint={`${(usage?.halls ?? 0).toLocaleString()} halls · ${users.length} accounts (${suspendedUsers} susp.)`}
          icon={<IconUsers width={18} height={18} />}
        />
        <Stat
          label="Plans Generated"
          value={(usage?.generations ?? 0).toLocaleString()}
          accent="violet"
          hint={`${(usage?.seats ?? 0).toLocaleString()} seats · ${bytes(usage?.storageUsedBytes)} stored`}
          icon={<IconActivity width={18} height={18} />}
        />
      </div>

      <div className="grid grid--2">
        <section className="card">
          <header className="card__head">
            <div>
              <h2>Subscription Tier Distribution</h2>
              <p className="small muted" style={{ margin: 0 }}>
                Active breakdown of registered colleges &amp; schools across plans.
              </p>
            </div>
            {onNavigate && (
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => onNavigate('workspaces')}
              >
                Manage Institutions
              </button>
            )}
          </header>
          <div className="card__body">
            {usage?.byPlan?.length ? (
              <div className="stack">
                {usage.byPlan.map((entry) => {
                  const total = Math.max(1, usage.institutions || 1)
                  const pct = Math.round((entry.count / total) * 100)
                  return (
                    <div key={entry.planCode}>
                      <div className="row row--between" style={{ marginBottom: 6 }}>
                        <span className="strong">{entry.planName}</span>
                        <Badge tone={entry.planCode === 'FREE' ? 'default' : 'brand'}>
                          {entry.count} {entry.count === 1 ? 'institution' : 'institutions'} ({pct}%)
                        </Badge>
                      </div>
                      <div className="meter" style={{ marginTop: 0 }}>
                        <div className="meter__fill" style={{ width: `${pct}%` }} />
                      </div>
                    </div>
                  )
                })}
              </div>
            ) : (
              <p className="muted small">No customer institutions registered yet.</p>
            )}
          </div>
        </section>

        <section className="card">
          <header className="card__head">
            <div>
              <h2>Recent Upgrade &amp; Payment Requests</h2>
              <p className="small muted" style={{ margin: 0 }}>
                Latest submissions across all institutions.
              </p>
            </div>
            {onNavigate && (
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => onNavigate('requests')}
              >
                View All
              </button>
            )}
          </header>
          <div className="card__body card__body--flush">
            {requests.length === 0 ? (
              <div style={{ padding: 20 }} className="small muted">
                No subscription upgrade requests have been submitted yet.
              </div>
            ) : (
              <div className="table-wrap table-wrap--responsive">
                <table>
                  <thead>
                    <tr>
                      <th>Institution</th>
                      <th>Target</th>
                      <th>Amount</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {requests.slice(0, 5).map((r) => (
                      <tr key={r.id}>
                        <td data-label="Institution">
                          <div className="td__cell-val strong">{r.institutionName}</div>
                        </td>
                        <td data-label="Target">
                          <div className="td__cell-val">
                            <Badge tone="brand">
                              {r.requestedPlanName || r.requestedPlanCode || 'Custom Limits'}
                            </Badge>
                          </div>
                        </td>
                        <td data-label="Amount">
                          <div className="td__cell-val mono small">
                            {formatMoney(r.paymentAmount, {
                              symbol: currencySymbol,
                              code: currencyCode,
                              decimals: 2,
                            })}
                          </div>
                        </td>
                        <td data-label="Status">
                          <div className="td__cell-val">
                            <StatusBadge status={r.status} />
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </section>
      </div>

      <section className="card">
        <header className="card__head">
          <h2>Recent Platform Activity</h2>
          {onNavigate && (
            <button
              type="button"
              className="btn btn--sm"
              onClick={() => onNavigate('activity')}
            >
              Full Audit Log
            </button>
          )}
        </header>
        <div className="card__body card__body--flush">
          <RecentActivity activity={activity} />
        </div>
      </section>
    </div>
  )
}
