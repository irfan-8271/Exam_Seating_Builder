import { useCallback, useEffect, useMemo, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { adminApi, planApi } from '../../api/endpoints'
import { useAuth } from '../../auth/AuthContext'
import { Alert, Loading } from '../../components/ui'
import Brand from '../../components/Brand'
import {
  IconActivity,
  IconBuilding,
  IconChart,
  IconLogout,
  IconMenu,
  IconUsers,
} from '../../components/Icons'
import OverviewTab from './OverviewTab'
import AccountsTab from './AccountsTab'
import WorkspacesTab from './WorkspacesTab'
import ActivityTab from './ActivityTab'

const TABS = [
  { id: 'overview', label: 'Overview', icon: <IconChart /> },
  { id: 'accounts', label: 'Accounts', icon: <IconUsers />, count: 'users' },
  { id: 'workspaces', label: 'Workspaces', icon: <IconBuilding />, count: 'institutions' },
  { id: 'activity', label: 'Activity', icon: <IconActivity /> },
]

const TITLES = {
  overview: ['Overview', 'How the service is doing right now'],
  accounts: ['Accounts', 'Every registered person, and what they may do'],
  workspaces: ['Workspaces', 'Every institution, its plan and its usage'],
  activity: ['Activity', 'What has been happening across the service'],
}

/**
 * The operator's dashboard.
 *
 * <p>Everything that used to need a database client: the accounts, their
 * roles, the limits each of them works under, the usage of every workspace,
 * and the feed of what has happened.</p>
 *
 * <p>Passwords are not here and cannot be reached from here — the operator
 * can set a new one, never read the old one.</p>
 */
export default function AdminPage() {
  const { user, isPlatformAdmin, signOut } = useAuth()
  const navigate = useNavigate()

  const [tab, setTab] = useState('overview')
  const [users, setUsers] = useState([])
  const [institutions, setInstitutions] = useState([])
  const [usage, setUsage] = useState(null)
  const [activity, setActivity] = useState([])
  const [plans, setPlans] = useState([])
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(true)
  const [menuOpen, setMenuOpen] = useState(false)

  const reload = useCallback(async () => {
    setError(null)
    try {
      const [userList, institutionList, usageView, activityList, planList] = await Promise.all([
        adminApi.users(),
        adminApi.institutions(),
        adminApi.usage(),
        adminApi.activity(120),
        planApi.list(),
      ])
      setUsers(userList)
      setInstitutions(institutionList)
      setUsage(usageView)
      setActivity(activityList)
      setPlans(planList)
    } catch (failure) {
      setError(failure.message || 'The administration view could not be loaded')
    } finally {
      setLoading(false)
    }
  }, [])

  // Only an administrator loads anything: a customer who lands here is sent
  // back to their own workspace, and should not see a wall of 403s on the way.
  useEffect(() => {
    if (!isPlatformAdmin) return
    reload()
  }, [reload, isPlatformAdmin])

  const counts = useMemo(
    () => ({ users: users.length, institutions: institutions.length }),
    [users, institutions],
  )

  if (!user) {
    return <Navigate to="/admin/login" replace />
  }
  // A customer account has no business here — send it back where it belongs.
  if (!isPlatformAdmin) {
    return <Navigate to="/dashboard" replace />
  }

  if (loading) {
    return <Loading label="Loading the administration…" />
  }

  const [title, subtitle] = TITLES[tab]

  return (
    <div className="admin-layout">
      <aside className={menuOpen ? 'admin-side admin-side--open' : 'admin-side'}>
        <div className="admin-side__brand">
          <Brand to="/admin" tagline="Control centre" />
        </div>

        <nav className="admin-side__nav">
          <p className="admin-side__label">The service</p>
          {TABS.map((entry) => (
            <button
              key={entry.id}
              type="button"
              className={
                entry.id === tab ? 'admin-nav-link admin-nav-link--active' : 'admin-nav-link'
              }
              onClick={() => {
                setTab(entry.id)
                setMenuOpen(false)
              }}
            >
              {entry.icon}
              {entry.label}
              {entry.count && (
                <span className="admin-nav-link__count">{counts[entry.count] ?? 0}</span>
              )}
            </button>
          ))}
        </nav>

        <div className="admin-side__foot">
          <div style={{ marginBottom: 8 }}>
            Signed in as <b style={{ color: '#e2e6f4' }}>{user.username}</b>
          </div>
          <button
            type="button"
            className="admin-nav-link"
            onClick={async () => {
              await signOut()
              navigate('/admin/login', { replace: true })
            }}
          >
            <IconLogout />
            Sign out
          </button>
        </div>
      </aside>

      <div className="admin-main">
        <header className="admin-top">
          <div className="row">
            <button
              type="button"
              className="icon-button sidebar-toggle"
              onClick={() => setMenuOpen((open) => !open)}
              aria-label="Menu"
            >
              <IconMenu />
            </button>
            <div>
              <h1>{title}</h1>
              <p>{subtitle}</p>
            </div>
          </div>
          <div className="row">
            <span className="badge badge--brand">Platform administrator</span>
          </div>
        </header>

        <div className="admin-body">
          {error && (
            <div style={{ marginBottom: 16 }}>
              <Alert tone="error">{error}</Alert>
            </div>
          )}

          {tab === 'overview' && (
            <OverviewTab usage={usage} activity={activity} users={users} institutions={institutions} />
          )}
          {tab === 'accounts' && (
            <AccountsTab users={users} plans={plans} onChanged={reload} />
          )}
          {tab === 'workspaces' && (
            <WorkspacesTab institutions={institutions} plans={plans} onChanged={reload} />
          )}
          {tab === 'activity' && <ActivityTab activity={activity} />}
        </div>
      </div>
    </div>
  )
}
