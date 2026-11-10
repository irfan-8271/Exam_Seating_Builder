import { useCallback, useEffect, useState } from 'react'
import { institutionApi, planApi, usageApi } from '../api/endpoints'
import { useAuth } from '../auth/AuthContext'
import { Alert, Badge, Card, Field, Loading, Table, moment } from '../components/ui'

/**
 * The institution's own settings: its name and contact address, the plan it
 * is on, and what it has used of it.
 */
export default function SettingsPage() {
  const { user, isPlatformAdmin } = useAuth()
  const [institution, setInstitution] = useState(null)
  const [plans, setPlans] = useState([])
  const [subscription, setSubscription] = useState(null)
  const [usage, setUsage] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const [busy, setBusy] = useState(false)

  const [form, setForm] = useState({ name: '', contactEmail: '' })

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [institutionData, planList, subscriptionData, usageData] = await Promise.all([
        institutionApi.current(),
        planApi.list(),
        planApi.subscription(),
        usageApi.usage(),
      ])
      setInstitution(institutionData)
      setPlans(planList || [])
      setSubscription(subscriptionData)
      setUsage(usageData)
      setForm({
        name: institutionData?.name || '',
        contactEmail: institutionData?.contactEmail || '',
      })
    } catch (failure) {
      setError(failure.message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function saveInstitution() {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const updated = await institutionApi.update({
        name: form.name.trim(),
        contactEmail: form.contactEmail.trim() || null,
      })
      setInstitution(updated)
      setNotice('The institution details were saved.')
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  async function changePlan(planCode) {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      await planApi.changePlan(planCode)
      setNotice(`The subscription is now ${planCode}.`)
      load()
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <Loading label="Loading your settings…" />

  return (
    <>
      <header className="topbar">
        <div className="topbar__title">
          <h1>Institution &amp; plan</h1>
          <p>{institution?.name}</p>
        </div>
      </header>

      <div className="content">
        {error && <Alert tone="error">{error}</Alert>}
        {notice && <Alert tone="success">{notice}</Alert>}

        <div className="grid grid--2">
          <Card title="Institution details">
            <Field label="Name">
              <input
                type="text"
                value={form.name}
                onChange={(event) => setForm({ ...form, name: event.target.value })}
                disabled={user?.role === 'INVIGILATOR'}
              />
            </Field>
            <Field label="Contact email">
              <input
                type="email"
                value={form.contactEmail}
                onChange={(event) => setForm({ ...form, contactEmail: event.target.value })}
                disabled={user?.role === 'INVIGILATOR'}
              />
            </Field>
            <Field label="Identifier" hint="The internal name of this institution — it cannot be changed.">
              <input type="text" value={institution?.slug || ''} readOnly disabled />
            </Field>
            {user?.role !== 'INVIGILATOR' && (
              <button
                type="button"
                className="btn btn--primary"
                onClick={saveInstitution}
                disabled={busy}
              >
                {busy && <span className="spinner" />}
                Save
              </button>
            )}
          </Card>

          <Card
            title="Subscription"
            actions={
              subscription ? (
                <Badge tone="primary">{subscription.planCode}</Badge>
              ) : null
            }
          >
            {subscription ? (
              <p className="small muted">
                {subscription.planName} · {subscription.status}
                {subscription.periodEnd
                  ? ` · renews ${moment(subscription.periodEnd)}`
                  : ''}
              </p>
            ) : (
              <p className="small muted">No subscription yet.</p>
            )}

            <Table
              rows={plans}
              columns={[
                {
                  key: 'name',
                  title: 'Plan',
                  render: (plan) => (
                    <span>
                      <strong>{plan.name}</strong>
                      {plan.code === subscription?.planCode && (
                        <Badge tone="success">current</Badge>
                      )}
                    </span>
                  ),
                },
                {
                  key: 'maxStudents',
                  title: 'Students',
                  numeric: true,
                  render: (plan) => plan.maxStudents,
                },
                {
                  key: 'maxGenerationsPerMonth',
                  title: 'Generations',
                  numeric: true,
                  render: (plan) => plan.maxGenerationsPerMonth,
                },
                {
                  key: 'action',
                  title: '',
                  render: (plan) =>
                    plan.code === subscription?.planCode ? (
                      <span className="faint small">—</span>
                    ) : (
                      <button
                        type="button"
                        className="btn btn--sm"
                        disabled={busy}
                        onClick={() => changePlan(plan.code)}
                      >
                        Move here
                      </button>
                    ),
                },
              ]}
            />
          </Card>
        </div>

        {usage && (
          <Card title="What you have used" subtitle="Against the current plan.">
            <div className="grid grid--4">
              <Usage
                label="Students"
                used={usage.studentsUsed}
                limit={usage.studentsLimit}
              />
              <Usage label="Halls" used={usage.roomsUsed} limit={usage.roomsLimit} />
              <Usage
                label="Generations"
                used={usage.generationsUsed}
                limit={usage.generationsLimit}
              />
              <Usage
                label="Storage (MB)"
                used={Math.round((usage.storageUsedBytes || 0) / (1024 * 1024))}
                limit={Math.round((usage.storageLimitBytes || 0) / (1024 * 1024))}
              />
            </div>
            {usage.periodEnd && (
              <p className="small faint" style={{ marginTop: 12 }}>
                The allowance runs until {moment(usage.periodEnd)}.
              </p>
            )}
          </Card>
        )}

        {isPlatformAdmin && (
          <Card title="Your account">
            <p className="small muted">
              Signed in as <strong>{user?.username}</strong> with the role{' '}
              <strong>{user?.role}</strong>.
            </p>
            <p className="small muted">
              The platform administration screen shows every institution and every user
              of the service.
            </p>
          </Card>
        )}
      </div>
    </>
  )
}

function Usage({ label, used, limit }) {
  const ratio = limit ? Math.min(1, used / limit) : 0
  const tone = ratio >= 0.9 ? 'meter__fill--danger' : ratio >= 0.7 ? 'meter__fill--warn' : ''
  return (
    <div className="stat">
      <div className="stat__label">{label}</div>
      <div className="stat__value">
        {used}
        {limit ? <span className="faint" style={{ fontSize: 15 }}> / {limit}</span> : null}
      </div>
      <div className="meter">
        <div className={'meter__fill' + tone} style={{ width: `${ratio * 100}%` }} />
      </div>
    </div>
  )
}
