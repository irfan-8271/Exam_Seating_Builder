import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { planApi, usageApi, studentApi, roomApi, seatingApi } from '../api/endpoints'
import { useAuth } from '../auth/AuthContext'
import { Alert, Card, EmptyState, Loading, Stat, moment, bytes } from '../components/ui'

/**
 * The dashboard: what this institution has, what it may still do, and where
 * to go next.
 */
export default function DashboardPage() {
  const { institution, user } = useAuth()
  const [usage, setUsage] = useState(null)
  const [generations, setGenerations] = useState([])
  const [counts, setCounts] = useState({ students: 0, rooms: 0, seats: 0 })
  const [plan, setPlan] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    let cancelled = false
    async function load() {
      try {
        const [usageData, generationData, planData, studentPage, roomPage] = await Promise.all([
          usageApi.usage(),
          usageApi.generations(),
          planApi.subscription(),
          studentApi.list({ page: 0, size: 1 }),
          roomApi.list({ page: 0, size: 200 }),
        ])
        if (cancelled) return
        setUsage(usageData)
        setGenerations(generationData || [])
        setPlan(planData)

        const rooms = roomPage?.content || []
        const seats = rooms.reduce(
          (total, room) =>
            total + room.rows * room.columns * (room.benchType === 'DUAL_SEATER' ? 2 : 1),
          0,
        )
        setCounts({
          students: studentPage?.totalElements ?? 0,
          rooms: rooms.length,
          seats,
        })
      } catch (failure) {
        if (!cancelled) setError(failure.message)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    load()
    return () => {
      cancelled = true
    }
  }, [])

  if (loading) return <Loading label="Loading your dashboard…" />

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Hello, {user?.fullName || user?.username}</h1>
          <p>
            {institution?.name} · {plan?.planName || plan?.planCode || 'No plan'}
          </p>
        </div>
        <Link className="btn btn--primary" to="/generate">
          Generate a seating plan
        </Link>
      </div>

      <div className="stack">
        {error && <Alert tone="error">{error}</Alert>}

        <div className="grid grid--4">
          <Stat
            label="Students"
            value={counts.students}
            limit={usage?.studentsLimit}
            hint={usage ? `${usage.studentsUsed} counted against the plan` : ''}
          />
          <Stat
            label="Halls"
            value={counts.rooms}
            limit={usage?.roomsLimit}
            hint={`${counts.seats} seats in total`}
          />
          <Stat
            label="Generations this period"
            value={usage?.generationsUsed ?? 0}
            limit={usage?.generationsLimit}
            hint={
              usage?.periodEnd
                ? `Resets ${moment(usage.periodEnd)}`
                : 'Monthly allowance'
            }
          />
          <Stat
            label="Storage"
            value={bytes(usage?.storageUsedBytes)}
            limit={usage?.storageLimitBytes ? bytes(usage.storageLimitBytes) : null}
            hint="Uploaded files"
          />
        </div>

        <div className="grid grid--2" style={{ marginTop: 18 }}>
          <Card title="Get started" subtitle="Four steps from an empty account to a seating plan.">
            <div className="stack">
              <Step
                number={1}
                title="Bring your roster"
                done={counts.students > 0}
                description="Upload an Excel sheet — the system works out how your sheet identifies students, and you confirm it. You can also add students by hand."
                to="/students"
              />
              <Step
                number={2}
                title="Describe your halls"
                done={counts.rooms > 0}
                description="Tell the system how many rows and columns each hall has, and whether the benches seat one or two."
                to="/halls"
              />
              <Step
                number={3}
                title="Generate"
                done={false}
                description="Choose the separation rules, the difficulty and which groups must be kept apart."
                to="/generate"
              />
              <Step
                number={4}
                title="Review and export"
                done={false}
                description="Look at the plan, move anyone who needs moving, and take away Excel or PDF."
                to="/plan"
              />
            </div>
          </Card>

          <Card
            title="Recent generations"
            actions={
              <Link className="btn btn--sm" to="/plan">
                Open the plan
              </Link>
            }
            flush
          >
            {generations.length === 0 ? (
              <EmptyState title="No plan yet">
                Generate your first seating plan — it will show up here.
              </EmptyState>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Subject</th>
                      <th className="numeric">Seats</th>
                      <th>Plan</th>
                      <th>When</th>
                    </tr>
                  </thead>
                  <tbody>
                    {generations.slice(0, 8).map((item) => (
                      <tr key={item.id}>
                        <td>{item.subjectCode || 'All subjects'}</td>
                        <td className="numeric">{item.seats}</td>
                        <td>{item.planCode}</td>
                        <td className="faint small">{moment(item.createdAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </div>
      </div>
    </>
  )
}

function Step({ number, title, description, to, done }) {
  return (
    <div className="step-row">
      <span
        className="step-row__mark"
        style={{
          background: done ? 'var(--success-soft)' : 'var(--primary-soft)',
          color: done ? 'var(--success)' : 'var(--brand-700)',
        }}
      >
        {done ? '✓' : number}
      </span>
      <div className="step-row__body">
        <div className="step-row__title">
          <strong>{title}</strong>
          {done && <span className="badge badge--ok">done</span>}
        </div>
        <div className="small muted">{description}</div>
      </div>
      <Link className="btn btn--sm" to={to}>
        Open
      </Link>
    </div>
  )
}
