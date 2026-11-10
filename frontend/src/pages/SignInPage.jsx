import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import AuthLayout, { PasswordField } from '../components/AuthLayout'
import { Alert, Field } from '../components/ui'
import { useAuth } from '../auth/AuthContext'

/**
 * The sign-in screen.
 *
 * <p>One form for everybody. An institution account lands on the dashboard;
 * a platform administrator is taken to the administration screens, because
 * there is nothing in a customer workspace for them to do.</p>
 */
export default function SignInPage() {
  const { signIn, user, isPlatformAdmin } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [rememberMe, setRememberMe] = useState(false)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  if (user) {
    return <Navigate to={isPlatformAdmin ? '/admin' : '/dashboard'} replace />
  }

  async function submit(event) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      const session = await signIn(username.trim(), password, rememberMe)
      const target =
        session.user?.role === 'PLATFORM_ADMIN'
          ? '/admin'
          : location.state?.from || '/dashboard'
      navigate(target, { replace: true })
    } catch (failure) {
      setError(failure.message || 'Sign in failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      title="Welcome back"
      lead="Sign in to your workspace."
      asideTitle="Your next seating plan is a couple of minutes away"
      asideLead="Bring the roster you already have. The system reads it, works out how your students are identified and lays out the halls under the rules you set."
      points={[
        'Any Excel structure — the system asks which column identifies a student',
        'Roll numbers are optional; no fixed assumptions about your sheet',
        'Seat moves by hand, checked against the same rules',
        'Excel and PDF exports ready for the exam day',
      ]}
      footer={
        <>
          New here? <Link to="/register">Create an account</Link>
          <div style={{ marginTop: 12 }}>
            <Link to="/admin/login" className="faint" style={{ fontWeight: 500 }}>
              Platform administration
            </Link>
          </div>
        </>
      }
    >
      {error && <Alert tone="error">{error}</Alert>}

      <form onSubmit={submit} noValidate>
        <Field label="Username">
          <input
            type="text"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            autoComplete="username"
            placeholder="your.username"
            required
            autoFocus
          />
        </Field>

        <PasswordField
          id="password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="current-password"
          placeholder="Your password"
          required
        />

        <div className="field field--inline">
          <label className="check">
            <input
              type="checkbox"
              checked={rememberMe}
              onChange={(event) => setRememberMe(event.target.checked)}
            />
            Keep me signed in for a month
          </label>
        </div>

        <button type="submit" className="btn btn--primary btn--lg btn--block" disabled={busy}>
          {busy && <span className="spinner" />}
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </AuthLayout>
  )
}
