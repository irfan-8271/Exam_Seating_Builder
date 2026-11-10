import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import AppShell from './components/AppShell'
import { Loading } from './components/ui'
import LandingPage from './pages/LandingPage'
import SignInPage from './pages/SignInPage'
import RegisterPage from './pages/RegisterPage'
import AdminLoginPage from './pages/AdminLoginPage'
import AdminPage from './pages/admin/AdminPage'
import DashboardPage from './pages/DashboardPage'
import StudentsPage from './pages/StudentsPage'
import HallsPage from './pages/HallsPage'
import GeneratePage from './pages/GeneratePage'
import SeatingPlanPage from './pages/SeatingPlanPage'
import ExportsPage from './pages/ExportsPage'
import SettingsPage from './pages/SettingsPage'
import TeamPage from './pages/TeamPage'

/** The pages that need a signed-in account. */
function Protected({ children }) {
  const { user, checking } = useAuth()
  const location = useLocation()

  if (checking) return <Loading label="Checking your session…" />
  if (!user) return <Navigate to="/signin" replace state={{ from: location.pathname }} />
  return children
}

/**
 * The customer's own area. A platform administrator has no workspace of their
 * own, so they are sent to the administration screens instead.
 */
function CustomerOnly({ children }) {
  const { user, checking, isPlatformAdmin } = useAuth()
  const location = useLocation()

  if (checking) return <Loading label="Checking your session…" />
  if (!user) return <Navigate to="/signin" replace state={{ from: location.pathname }} />
  if (isPlatformAdmin) return <Navigate to="/admin" replace />
  return children
}

export default function App() {
  return (
    <Routes>
      {/* The public pages */}
      <Route path="/" element={<LandingPage />} />
      <Route path="/signin" element={<SignInPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/admin/login" element={<AdminLoginPage />} />

      {/* The operator's own area, with its own layout */}
      <Route
        path="/admin"
        element={
          <Protected>
            <AdminPage />
          </Protected>
        }
      />

      {/* The customer's area */}
      <Route
        element={
          <CustomerOnly>
            <AppShell />
          </CustomerOnly>
        }
      >
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/students" element={<StudentsPage />} />
        <Route path="/halls" element={<HallsPage />} />
        <Route path="/generate" element={<GeneratePage />} />
        <Route path="/plan" element={<SeatingPlanPage />} />
        <Route path="/exports" element={<ExportsPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/team" element={<TeamPage />} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
