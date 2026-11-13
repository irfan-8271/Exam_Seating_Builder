import { useMemo, useState } from 'react'
import { Badge, moment } from '../../components/ui'

const LABELS = {
  REGISTRATION: 'created an account',
  SIGN_IN: 'signed in',
  SIGN_OUT: 'signed out',
  GENERATION: 'generated a seating plan',
  PLAN_CHANGE: 'changed the plan',
  LIMIT_CHANGE: 'changed the limits',
  USER_CHANGE: 'changed an account',
  ACCOUNT_SUSPENDED: 'suspended a workspace',
  ACCOUNT_RESTORED: 'restored a workspace',
  UPGRADE_REQUESTED: 'submitted an upgrade request',
  UPGRADE_APPROVED: 'approved an upgrade request',
  UPGRADE_REJECTED: 'rejected an upgrade request',
  PAYMENT_CONFIG_UPDATED: 'updated payment settings',
}

const TYPES = [
  { value: 'ALL', label: 'Everything' },
  { value: 'UPGRADE_REQUESTED', label: 'Upgrade requests' },
  { value: 'UPGRADE_APPROVED', label: 'Upgrade approvals' },
  { value: 'UPGRADE_REJECTED', label: 'Upgrade rejections' },
  { value: 'PAYMENT_CONFIG_UPDATED', label: 'Payment config' },
  { value: 'REGISTRATION', label: 'Registrations' },
  { value: 'SIGN_IN', label: 'Sign-ins' },
  { value: 'GENERATION', label: 'Plans generated' },
  { value: 'LIMIT_CHANGE', label: 'Limit changes' },
  { value: 'USER_CHANGE', label: 'Account changes' },
]

/**
 * What has been happening across the service.
 *
 * <p>Written by the backend as things happen — registrations, sign-ins,
 * generated plans and the changes an operator makes — so the answer to "what
 * has this person been doing?" is one click away instead of a query.</p>
 */
export default function ActivityTab({ activity }) {
  const [filter, setFilter] = useState('ALL')

  const rows = useMemo(() => {
    const list = activity || []
    if (filter === 'ALL') return list
    return list.filter((entry) => entry.type === filter)
  }, [activity, filter])

  return (
    <section className="card">
      <div className="toolbar">
        <select
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
          style={{ width: 'auto', minWidth: 190 }}
        >
          {TYPES.map((entry) => (
            <option key={entry.value} value={entry.value}>
              {entry.label}
            </option>
          ))}
        </select>
        <span className="toolbar__spacer" />
        <span className="muted small">
          {rows.length} {rows.length === 1 ? 'entry' : 'entries'}
        </span>
      </div>

      <div className="card__body card__body--flush">
        {rows.length === 0 ? (
          <p className="muted" style={{ padding: 32, textAlign: 'center' }}>
            Nothing matches that filter yet.
          </p>
        ) : (
          rows.map((entry) => (
            <div className="activity" key={entry.id}>
              <span className={`activity__dot activity__dot--${entry.type.toLowerCase()}`} />
              <div className="activity__body">
                <div className="activity__title">
                  <b>{entry.username || 'Someone'}</b> {LABELS[entry.type] || entry.type}
                  {entry.institutionName && (
                    <>
                      {' '}
                      <Badge>{entry.institutionName}</Badge>
                    </>
                  )}
                </div>
                {entry.detail && <div className="activity__detail">{entry.detail}</div>}
              </div>
              <span className="activity__time">{moment(entry.occurredAt)}</span>
            </div>
          ))
        )}
      </div>
    </section>
  )
}
