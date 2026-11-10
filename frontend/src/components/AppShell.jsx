import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

const SECTIONS = [
  {
    title: 'Overview',
    links: [
      { to: '/', label: 'Dashboard', icon: '▤', end: true },
    ],
  },
  {
    title: 'Prepare',
    links: [
      { to: '/students', label: 'Students', icon: '☰' },
      { to: '/halls', label: 'Halls & benches', icon: '▦' },
    ],
  },
  {
    title: 'Exam',
    links: [
      { to: '/generate', label: 'Generate a plan', icon: '✦' },
      { to: '/plan', label: 'Seating plan', icon: '▧' },
      { to: '/exports', label: 'Export', icon: '⭳' },
    ],
  },
  {
    title: 'Account',
    links: [
      { to: '/settings', label: 'Institution & plan', icon: '⚙' },
    ],
  },
]

const ADMIN_SECTION = {
  title: 'Platform',
  links: [{ to: '/admin', label: 'Administration', icon: '★' }],
}

/**
 * The frame around every signed-in page.
 *
 * The footer carries the small link to the administrator's sign-in — out of
 * the way of the ordinary user, but there when it is needed.
 */
export default function AppShell({ children }) {
  const { user, institution, signOut, isPlatformAdmin } = useAuth()
  const navigate = useNavigate()

  const sections = isPlatformAdmin ? [...SECTIONS, ADMIN_SECTION] : SECTIONS

  async function handleSignOut() {
    await signOut()
    navigate('/signin', { replace: true })
  }

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="sidebar__brand">
          <h1>Exam Seating Builder</h1>
          <span>{institution?.name || 'Institution'}</span>
        </div>

        <nav className="sidebar__nav">
          {sections.map((section) => (
            <div key={section.title}>
              <div className="sidebar__section">{section.title}</div>
              {section.links.map((link) => (
                <NavLink
                  key={link.to}
                  to={link.to}
                  end={link.end}
                  className={({ isActive }) => (isActive ? 'navlink navlink--active' : 'navlink')}
                >
                  <span className="navlink__icon">{link.icon}</span>
                  {link.label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>

        <div className="sidebar__foot">
          <div style={{ marginBottom: 6 }}>
            Signed in as <strong>{user?.username}</strong>
            <div className="faint">{user?.role}</div>
          </div>
          <button type="button" onClick={handleSignOut}>
            Sign out
          </button>
        </div>
      </aside>

      <div className="main">
        {/* The ordinary pages arrive through the router; the administrator's
            page is handed over directly. */}
        {children ?? <Outlet />}
      </div>
    </div>
  )
}
