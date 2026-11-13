import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { paymentApi, planApi, roomApi, studentApi, usageApi } from '../api/endpoints'
import { useAuth } from '../auth/AuthContext'
import {
  Alert,
  Badge,
  Card,
  EmptyState,
  Loading,
  Stat,
  StatusBadge,
  Table,
  bytes,
  moment,
} from '../components/ui'
import {
  IconActivity,
  IconGrid,
  IconSparkle,
  IconUpload,
  IconUsers,
  IconWand,
} from '../components/Icons'

export default function DashboardPage() {
  const { institution, user } = useAuth()
  const [usage, setUsage] = useState(null)
  const [generations, setGenerations] = useState([])
  const [counts, setCounts] = useState({ students: 0, rooms: 0, seats: 0 })
  const [plan, setPlan] = useState(null)
  const [pendingRequests, setPendingRequests] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    let cancelled = false
    async function load() {
      try {
        const [usageData, generationData, planData, studentPage, roomPage, myReqs] =
          await Promise.all([
            usageApi.usage(),
            usageApi.generations(),
            planApi.subscription(),
            studentApi.list({ page: 0, size: 1 }),
            roomApi.list({ page: 0, size: 200 }),
            paymentApi.myRequests().catch(() => []),
          ])
        if (cancelled) return
        setUsage(usageData)
        setGenerations(generationData || [])
        setPlan(planData)
        setPendingRequests((myReqs || []).filter((r) => r.status === 'PENDING').length)

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

  if (loading) return <Loading label="Loading your workspace dashboard…" />

  return (
    <>
      <div className="dash-hero">
        <div className="dash-hero__text">
          <div className="row" style={{ gap: 8, marginBottom: 8 }}>
            <Badge tone="brand">{plan?.planName || plan?.planCode || 'Free'} Plan</Badge>
            {plan?.status && <StatusBadge status={plan.status} />}
            {pendingRequests > 0 && (
              <Badge tone="warning">
                {pendingRequests} Pending Upgrade {pendingRequests === 1 ? 'Request' : 'Requests'}
              </Badge>
            )}
          </div>
          <h1>Welcome back, {user?.fullName || user?.username}</h1>
          <p>
            {institution?.name} · Dynamic examination seating workspace with strict cohort
            separation &amp; automated hall exports.
          </p>
        </div>
        <div className="btn-row">
          <Link className="btn btn--ghost-light" to="/settings">
            <IconSparkle width={16} height={16} />
            Upgrade / Limits
          </Link>
          <Link className="btn btn--primary" to="/generate">
            <IconWand width={16} height={16} />
            Generate Seating Plan
          </Link>
        </div>
      </div>

      <div className="stack">
        {error && <Alert tone="error">{error}</Alert>}

        <div className="grid grid--4">
          <Stat
            label="Students"
            value={counts.students}
            limit={usage?.studentsLimit}
            accent="brand"
            icon={<IconUsers width={18} height={18} />}
            hint={usage ? `${usage.studentsUsed} counted against quota` : ''}
          />
          <Stat
            label="Exam Halls"
            value={counts.rooms}
            limit={usage?.roomsLimit}
            accent="emerald"
            icon={<IconGrid width={18} height={18} />}
            hint={`${counts.seats.toLocaleString()} physical seats`}
          />
          <Stat
            label="Generations This Period"
            value={usage?.generationsUsed ?? 0}
            limit={usage?.generationsLimit}
            accent="amber"
            icon={<IconActivity width={18} height={18} />}
            hint={usage?.periodEnd ? `Resets ${moment(usage.periodEnd)}` : 'Monthly allowance'}
          />
          <Stat
            label="Storage Used"
            value={bytes(usage?.storageUsedBytes)}
            limit={usage?.storageLimitBytes ? bytes(usage.storageLimitBytes) : null}
            accent="violet"
            icon={<IconUpload width={18} height={18} />}
            hint="Uploaded Excel sheets"
          />
        </div>

        <div className="grid grid--2">
          <Card
            title="Examination Setup Workflow"
            subtitle="Four steps from raw spreadsheet to invigilator-ready hall plans."
            accent="brand"
          >
            <div className="stack">
              <Step
                number={1}
                title="Import or Add Student Roster"
                done={counts.students > 0}
                description="Upload any school or university Excel sheet — dynamic column discovery maps student identifiers and classmate cohorts automatically."
                to="/students"
              />
              <Step
                number={2}
                title="Configure Examination Halls"
                done={counts.rooms > 0}
                description="Define rows, columns, and single- or dual-seater benches for every examination room."
                to="/halls"
              />
              <Step
                number={3}
                title="Generate with Separation Rules"
                done={generations.length > 0}
                description="Pick Normal, Intermediate, or Extreme / Rigid separation with live empty-seat & capacity diagnostics."
                to="/generate"
              />
              <Step
                number={4}
                title="Inspect & Export Sheets"
                done={generations.length > 0}
                description="Perform validated drag/click seat moves and export Excel workbooks, door notices, and invigilator sheets."
                to="/plan"
              />
            </div>
          </Card>

          <Card
            title="Recent Seating Generations"
            subtitle="Latest plans solved in this workspace."
            actions={
              <Link className="btn btn--sm" to="/plan">
                Open Plan
              </Link>
            }
            flush
          >
            {generations.length === 0 ? (
              <EmptyState title="No seating plans generated yet">
                Once you generate your first arrangement, each session round is logged here.
              </EmptyState>
            ) : (
              <Table
                columns={[
                  {
                    key: 'scopeKey',
                    title: 'Round / Scope',
                    render: (item) => <strong>{item.scopeKey || 'Whole roster'}</strong>,
                  },
                  {
                    key: 'seats',
                    title: 'Seated',
                    numeric: true,
                    render: (item) => item.seats,
                  },
                  {
                    key: 'planCode',
                    title: 'Plan Tier',
                    render: (item) => <Badge tone="brand">{item.planCode}</Badge>,
                  },
                  {
                    key: 'createdAt',
                    title: 'Generated',
                    render: (item) => <span className="faint small">{moment(item.createdAt)}</span>,
                  },
                ]}
                rows={generations.slice(0, 8)}
              />
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
          {done && <Badge tone="success">Complete</Badge>}
        </div>
        <div className="small muted">{description}</div>
      </div>
      <Link className="btn btn--sm" to={to}>
        Open
      </Link>
    </div>
  )
}
