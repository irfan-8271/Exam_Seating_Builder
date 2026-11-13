import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import Brand from './Brand'
import {
  IconClose,
  IconCreditCard,
  IconDashboard,
  IconDownload,
  IconGrid,
  IconLayout,
  IconLogout,
  IconMenu,
  IconSettings,
  IconUser,
  IconUsers,
  IconWand,
} from './Icons'

const SECTIONS = [
  {
    title: 'Overview',
    links: [{ to: '/dashboard', label: 'Dashboard', icon: <IconDashboard />, end: true }],
  },
  {
    title: 'Data Setup',
    links: [
      { to: '/students', label: 'Students & Roster', icon: <IconUsers /> },
      { to: '/halls', label: 'Halls & Benches', icon: <IconGrid /> },
    ],
  },
  {
    title: 'Examination',
    links: [
      { to: '/generate', label: 'Generate Plan', icon: <IconWand /> },
      { to: '/plan', label: 'Seating Plan', icon: <IconLayout /> },
      { to: '/exports', label: 'Export Sheets', icon: <IconDownload /> },
    ],
  },
  {
    title: 'Workspace',
    links: [
      { to: '/team', label: 'Team & Roles', icon: <IconUser /> },
      { to: '/settings', label: 'Plan & Billing', icon: <IconCreditCard /> },
    ],
  },
]

const MOBILE_TABS = [
  { to: '/dashboard', label: 'Home', icon: <IconDashboard width={18} height={18} />, end: true },
  { to: '/students', label: 'Students', icon: <IconUsers width={18} height={18} /> },
  { to: '/halls', label: 'Halls', icon: <IconGrid width={18} height={18} /> },
  { to: '/generate', label: 'Generate', icon: <IconWand width={18} height={18} /> },
  { to: '/plan', label: 'Plan', icon: <IconLayout width={18} height={18} /> },
  { to: '/settings', label: 'Billing', icon: <IconSettings width={18} height={18} /> },
]

export default function AppShell({ children }) {
  const { user, institution, signOut } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [open, setOpen] = useState(false)

  useEffect(() => {
    setOpen(false)
  }, [location.pathname])

  async function handleSignOut() {
    await signOut()
    navigate('/signin', { replace: true })
  }

  const current = SECTIONS.flatMap((section) => section.links).find(
    (link) => link.to === location.pathname,
  )

  return (
    <div className="app">
      {open && (
        <div
          className="drawer-scrim"
          onClick={() => setOpen(false)}
          aria-hidden="true"
        />
      )}

      <aside className={open ? 'sidebar sidebar--open' : 'sidebar'}>
        <div className="sidebar__brand">
          <Brand to="/dashboard" />
          <button
            type="button"
            className="icon-button sidebar-close"
            onClick={() => setOpen(false)}
            aria-label="Close navigation"
          >
            <IconClose width={18} height={18} />
          </button>
        </div>

        <nav className="sidebar__nav">
          {SECTIONS.map((section) => (
            <div key={section.title}>
              <div className="sidebar__section">{section.title}</div>
              {section.links.map((link) => (
                <NavLink
                  key={link.to}
                  to={link.to}
                  end={link.end}
                  onClick={() => setOpen(false)}
                  className={({ isActive }) => (isActive ? 'nav-link nav-link--active' : 'nav-link')}
                >
                  {link.icon}
                  <span>{link.label}</span>
                </NavLink>
              ))}
            </div>
          ))}
        </nav>

        <div className="sidebar__foot">
          <div className="sidebar__user">
            <span className="avatar avatar--sidebar">
              {(user?.fullName || user?.username || '?').slice(0, 2).toUpperCase()}
            </span>
            <div style={{ minWidth: 0, flex: 1 }}>
              <div className="sidebar__username">
                {user?.fullName || user?.username}
              </div>
              <div className="sidebar__sub">
                {institution?.name || user?.role}
              </div>
            </div>
          </div>
          <button
            type="button"
            className="btn btn--sm sidebar__logout"
            onClick={handleSignOut}
          >
            <IconLogout width={15} height={15} />
            Sign out
          </button>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <div className="row" style={{ minWidth: 0 }}>
            <button
              type="button"
              className="icon-button sidebar-toggle"
              onClick={() => setOpen((value) => !value)}
              aria-label="Open menu"
            >
              <IconMenu />
            </button>
            <span className="topbar__title">{current?.label || 'Exam Seating Builder'}</span>
          </div>
          <div className="topbar__actions">
            <span className="badge badge--workspace" title={institution?.name}>
              {institution?.name || 'Workspace'}
            </span>
            <span className="badge badge--brand hide-xs">{user?.role}</span>
          </div>
        </header>

        <main className="content">{children ?? <Outlet />}</main>

        <nav className="mobile-tabbar" aria-label="Quick navigation">
          {MOBILE_TABS.map((tab) => (
            <NavLink
              key={tab.to}
              to={tab.to}
              end={tab.end}
              className={({ isActive }) =>
                isActive ? 'mobile-tabbar__item mobile-tabbar__item--active' : 'mobile-tabbar__item'
              }
            >
              {tab.icon}
              <span>{tab.label}</span>
            </NavLink>
          ))}
        </nav>
      </div>
    </div>
  )
}
