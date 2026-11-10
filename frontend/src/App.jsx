import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import AppShell from './components/AppShell'
import { Loading } from './components/ui'
import SignInPage from './pages/SignInPage'
import DashboardPage from './pages/DashboardPage'
import StudentsPage from './pages/StudentsPage'
import HallsPage from './pages/HallsPage'
import GeneratePage from './pages/GeneratePage'
import SeatingPlanPage from './pages/SeatingPlanPage'
import ExportsPage from './pages/ExportsPage'
import SettingsPage from './pages/SettingsPage'
import AdminPage from './pages/AdminPage'

/** The pages that need a signed-in account. */
function Protected({ children }) {
  const { user, checking } = useAuth()
  const location = useLocation()

  if (checking) return <Loading label="Checking your session…" />
  if (!user) return <Navigate to="/signin" replace state={{ from: location.pathname }} />
  return children
}

/** The operator's pages — only a platform administrator may open them. */
function AdminOnly({ children }) {
  const { user, checking, isPlatformAdmin } = useAuth()

  if (checking) return <Loading label="Checking your session…" />
  if (!user) return <Navigate to="/signin" replace />
  if (!isPlatformAdmin) {
    return (
      <div className="content" style={{ maxWidth: 640, margin: '0 auto', paddingTop: 60 }}>
        <div className="alert alert--error">
          This screen is reserved for the platform administrator. Sign in with an
          administrator account to open it.
        </div>
      </div>
    )
  }
  return <AppShell>{children}</AppShell>
}

export default function App() {
  return (
    <Routes>
      <Route path="/signin" element={<SignInPage />} />

      <Route
        path="/admin"
        element={
          <AdminOnly>
            <AdminPage />
          </AdminOnly>
        }
      />

      <Route
        element={
          <Protected>
            <AppShell />
          </Protected>
        }
      >
        <Route path="/" element={<DashboardPage />} />
        <Route path="/students" element={<StudentsPage />} />
        <Route path="/halls" element={<HallsPage />} />
        <Route path="/generate" element={<GeneratePage />} />
        <Route path="/plan" element={<SeatingPlanPage />} />
        <Route path="/exports" element={<ExportsPage />} />
        <Route path="/settings" element={<SettingsPage />} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
