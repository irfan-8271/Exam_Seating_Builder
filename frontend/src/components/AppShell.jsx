import { useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import Brand from './Brand'
import {
  IconDashboard,
  IconDownload,
  IconGrid,
  IconLayout,
  IconLogout,
  IconMenu,
  IconSettings,
  IconTable,
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
    title: 'Prepare',
    links: [
      { to: '/students', label: 'Students', icon: <IconUsers /> },
      { to: '/halls', label: 'Halls & benches', icon: <IconGrid /> },
    ],
  },
  {
    title: 'Exam',
    links: [
      { to: '/generate', label: 'Generate a plan', icon: <IconWand /> },
      { to: '/plan', label: 'Seating plan', icon: <IconLayout /> },
      { to: '/exports', label: 'Export', icon: <IconDownload /> },
    ],
  },
  {
    title: 'Account',
    links: [
      { to: '/team', label: 'Team', icon: <IconUser /> },
      { to: '/settings', label: 'Institution & plan', icon: <IconSettings /> },
    ],
  },
]

/**
 * The frame around every signed-in page.
 *
 * The sidebar carries the sections of the work; the top bar carries where you
 * are and who you are. On a phone the sidebar folds away behind a button.
 */
export default function AppShell({ children }) {
  const { user, institution, signOut } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [open, setOpen] = useState(false)

  async function handleSignOut() {
    await signOut()
    navigate('/signin', { replace: true })
  }

  /** The name of the screen, from the route. */
  const current = SECTIONS.flatMap((section) => section.links).find((link) =>
    link.to === location.pathname,
  )

  return (
    <div className="app">
      <aside className={open ? 'sidebar sidebar--open' : 'sidebar'}>
        <div className="sidebar__brand">
          <Brand to="/dashboard" />
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
                  {link.label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>

        <div className="sidebar__foot">
          <div style={{ marginBottom: 8 }}>
            <div className="row" style={{ gap: 10 }}>
              <span className="avatar">
                {(user?.fullName || user?.username || '?').slice(0, 2).toUpperCase()}
              </span>
              <div style={{ minWidth: 0 }}>
                <div className="strong small" style={{ color: 'var(--ink-800)' }}>
                  {user?.fullName || user?.username}
                </div>
                <div className="faint" style={{ fontSize: 'var(--text-2xs)' }}>
                  {institution?.name || user?.role}
                </div>
              </div>
            </div>
          </div>
          <button type="button" className="btn btn--sm btn--ghost" onClick={handleSignOut}>
            <IconLogout width={15} height={15} />
            Sign out
          </button>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <div className="row">
            <button
              type="button"
              className="icon-button sidebar-toggle"
              onClick={() => setOpen((value) => !value)}
              aria-label="Menu"
            >
              <IconMenu />
            </button>
            <span className="topbar__title">{current?.label || 'Exam Seating Builder'}</span>
          </div>
          <div className="topbar__actions">
            <span className="badge">{institution?.name || 'Your workspace'}</span>
          </div>
        </header>

        {/* The ordinary pages arrive through the router. */}
        <main className="content">{children ?? <Outlet />}</main>
      </div>
    </div>
  )
}
