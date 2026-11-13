import { useCallback, useEffect, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { adminApi, planApi } from '../../api/endpoints'
import Brand from '../../components/Brand'
import { Alert, Loading } from '../../components/ui'
import {
  IconActivity,
  IconBuilding,
  IconChart,
  IconClose,
  IconCreditCard,
  IconLogout,
  IconMenu,
  IconQrCode,
  IconUsers,
} from '../../components/Icons'
import OverviewTab from './OverviewTab'
import AccountsTab from './AccountsTab'
import WorkspacesTab from './WorkspacesTab'
import ActivityTab from './ActivityTab'
import RequestsTab from './RequestsTab'
import PaymentConfigTab from './PaymentConfigTab'

const TABS = {
  overview: {
    title: 'SaaS Platform Overview',
    subtitle: 'Registered institutions, active plans, pending payment verifications, and platform activity.',
  },
  requests: {
    title: 'Upgrade & Payment Requests',
    subtitle: 'Verify UTR / payment proofs and approve or reject subscription & capacity requests.',
  },
  payments: {
    title: 'Payment Verification Settings',
    subtitle: 'Configure UPI ID, QR code image, payment instructions, and screenshot policy.',
  },
  workspaces: {
    title: 'Registered Institutions',
    subtitle: 'Manage college & school subscriptions, usage quotas, custom limits, and status.',
  },
  accounts: {
    title: 'User Accounts',
    subtitle: 'Inspect roles, per-user limit overrides, and account access across all tenants.',
  },
  activity: {
    title: 'Platform Activity Audit',
    subtitle: 'Chronological audit feed of registrations, sign-ins, upgrades, and generations.',
  },
}

export default function AdminPage() {
  const { user, isPlatformAdmin, signOut } = useAuth()
  const navigate = useNavigate()

  const [tab, setTab] = useState('overview')
  const [menuOpen, setMenuOpen] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const [usage, setUsage] = useState(null)
  const [institutions, setInstitutions] = useState([])
  const [users, setUsers] = useState([])
  const [activity, setActivity] = useState([])
  const [plans, setPlans] = useState([])
  const [requests, setRequests] = useState([])
  const [paymentConfig, setPaymentConfig] = useState(null)

  const reload = useCallback(async () => {
    setError(null)
    try {
      const [u, inst, usr, act, pl, reqs, payCfg] = await Promise.all([
        adminApi.usage(),
        adminApi.institutions(),
        adminApi.users(),
        adminApi.activity(120),
        planApi.list(),
        adminApi.subscriptionRequests('ALL'),
        adminApi.paymentConfig(),
      ])
      setUsage(u)
      setInstitutions(inst || [])
      setUsers(usr || [])
      setActivity(act || [])
      setPlans(pl || [])
      setRequests(reqs || [])
      setPaymentConfig(payCfg)
    } catch (failure) {
      setError(failure.message || 'Could not load administration console data.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (isPlatformAdmin) reload()
  }, [isPlatformAdmin, reload])

  if (!user) return <Navigate to="/admin/login" replace />
  if (!isPlatformAdmin) return <Navigate to="/dashboard" replace />
  if (loading) return <Loading label="Loading SaaS administration console…" />

  const pendingCount = requests.filter((r) => r.status === 'PENDING').length
  const { title, subtitle } = TABS[tab] || TABS.overview

  function choose(next) {
    setTab(next)
    setMenuOpen(false)
  }

  return (
    <div className="admin-layout">
      {menuOpen && (
        <div
          className="drawer-scrim"
          onClick={() => setMenuOpen(false)}
          aria-hidden="true"
        />
      )}

      <aside className={menuOpen ? 'admin-side admin-side--open' : 'admin-side'}>
        <div className="admin-side__brand">
          <div className="row row--between" style={{ width: '100%' }}>
            <Brand to="/admin" />
            <button
              type="button"
              className="icon-button sidebar-close"
              onClick={() => setMenuOpen(false)}
              aria-label="Close menu"
            >
              <IconClose width={18} height={18} />
            </button>
          </div>
          <span className="brand__label">SaaS Operator Console</span>
        </div>

        <nav className="admin-side__nav">
          <div className="admin-side__label">Operations</div>
          <button
            type="button"
            className={tab === 'overview' ? 'admin-nav-link admin-nav-link--active' : 'admin-nav-link'}
            onClick={() => choose('overview')}
          >
            <IconChart />
            <span>Overview</span>
          </button>

          <button
            type="button"
            className={tab === 'requests' ? 'admin-nav-link admin-nav-link--active' : 'admin-nav-link'}
            onClick={() => choose('requests')}
          >
            <IconCreditCard />
            <span style={{ flex: 1 }}>Upgrade Requests</span>
            {pendingCount > 0 && (
              <span className="nav-counter nav-counter--warn">{pendingCount}</span>
            )}
          </button>

          <button
            type="button"
            className={tab === 'payments' ? 'admin-nav-link admin-nav-link--active' : 'admin-nav-link'}
            onClick={() => choose('payments')}
          >
            <IconQrCode />
            <span>Payment Settings</span>
          </button>

          <div className="admin-side__label">Tenants &amp; Governance</div>
          <button
            type="button"
            className={tab === 'workspaces' ? 'admin-nav-link admin-nav-link--active' : 'admin-nav-link'}
            onClick={() => choose('workspaces')}
          >
            <IconBuilding />
            <span style={{ flex: 1 }}>Institutions</span>
            <span className="nav-counter">{institutions.length}</span>
          </button>

          <button
            type="button"
            className={tab === 'accounts' ? 'admin-nav-link admin-nav-link--active' : 'admin-nav-link'}
            onClick={() => choose('accounts')}
          >
            <IconUsers />
            <span style={{ flex: 1 }}>Accounts</span>
            <span className="nav-counter">{users.length}</span>
          </button>

          <button
            type="button"
            className={tab === 'activity' ? 'admin-nav-link admin-nav-link--active' : 'admin-nav-link'}
            onClick={() => choose('activity')}
          >
            <IconActivity />
            <span>Activity Log</span>
          </button>
        </nav>

        <div className="admin-side__foot">
          <div style={{ marginBottom: 10 }}>
            Signed in as <strong style={{ color: '#fff' }}>{user.username}</strong>
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
            <span>Sign out</span>
          </button>
        </div>
      </aside>

      <div className="admin-main">
        <header className="admin-top">
          <div className="row" style={{ minWidth: 0 }}>
            <button
              type="button"
              className="icon-button sidebar-toggle"
              onClick={() => setMenuOpen((open) => !open)}
              aria-label="Open menu"
            >
              <IconMenu />
            </button>
            <div style={{ minWidth: 0 }}>
              <h1>{title}</h1>
              <p className="hide-xs">{subtitle}</p>
            </div>
          </div>
          <div className="row">
            {pendingCount > 0 && tab !== 'requests' && (
              <button
                type="button"
                className="btn btn--sm btn--primary"
                onClick={() => choose('requests')}
              >
                {pendingCount} Pending {pendingCount === 1 ? 'Verification' : 'Verifications'}
              </button>
            )}
            <span className="badge badge--brand hide-xs">PLATFORM_ADMIN</span>
          </div>
        </header>

        <div className="admin-body">
          {error && (
            <div style={{ marginBottom: 16 }}>
              <Alert tone="error">{error}</Alert>
            </div>
          )}

          {tab === 'overview' && (
            <OverviewTab
              usage={usage}
              activity={activity}
              users={users}
              institutions={institutions}
              requests={requests}
              onNavigate={choose}
            />
          )}
          {tab === 'requests' && (
            <RequestsTab
              requests={requests}
              institutions={institutions}
              onChanged={reload}
            />
          )}
          {tab === 'payments' && (
            <PaymentConfigTab config={paymentConfig} onChanged={reload} />
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
