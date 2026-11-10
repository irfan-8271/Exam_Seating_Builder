import { useEffect, useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import AuthLayout, { PasswordField } from '../components/AuthLayout'
import { Alert, Field } from '../components/ui'
import { useAuth } from '../auth/AuthContext'
import { adminApi } from '../api/endpoints'
import { IconAlert } from '../components/Icons'

/**
 * The administration sign-in.
 *
 * <p>A door of its own, with an endpoint of its own: only an account with
 * the platform administrator role gets through it. Anyone else is told which
 * door to use, rather than being met with a vague failure.</p>
 *
 * <p>The screen also checks whether an administrator is configured at all,
 * so an installation without one says so plainly instead of pretending.</p>
 */
export default function AdminLoginPage() {
  const { platformSignIn, user, isPlatformAdmin } = useAuth()
  const navigate = useNavigate()

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState(null)

  // Is an administrator configured on this installation?
  useEffect(() => {
    let cancelled = false
    adminApi
      .status()
      .then((value) => {
        if (!cancelled) setStatus(value)
      })
      .catch(() => {
        if (!cancelled) setStatus(null)
      })
    return () => {
      cancelled = true
    }
  }, [])

  if (user && isPlatformAdmin) {
    return <Navigate to="/admin" replace />
  }

  async function submit(event) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await platformSignIn(username.trim(), password)
      navigate('/admin', { replace: true })
    } catch (failure) {
      setError(failure.message || 'Sign in failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      admin
      title="Platform administration"
      lead="The operator's own door."
      asideTitle="Everything about the service, on one screen"
      asideLead="Every institution, every account, what each of them generates, and the limits they work under — without opening the database."
      points={[
        'Every registered account, with its role and its last sign-in',
        'Raise or lower a limit for one person or one institution',
        'Suspend an account, change a role, reset a password',
        'The activity feed: registrations, sign-ins, plans, changes',
      ]}
      footer={
        <>
          <Link to="/signin">← back to the normal sign-in</Link>
        </>
      }
    >
      {status && !status.enabled && (
        <div className="admin-note">
          <IconAlert width={16} height={16} />
          <span>
            No platform administrator is configured on this installation yet.
            Set <code>PLATFORM_ADMIN_USERNAME</code> and{' '}
            <code>PLATFORM_ADMIN_PASSWORD</code> in the environment (or in a
            <code> .env</code> file) and restart the service.
          </span>
        </div>
      )}

      {status && status.enabled && (
        <div className="admin-note">
          <IconAlert width={16} height={16} />
          <span>
            The administrator account on this installation is{' '}
            <b>{status.username}</b>.
          </span>
        </div>
      )}

      {error && <Alert tone="error">{error}</Alert>}

      <form onSubmit={submit} noValidate>
        <Field label="Administrator username">
          <input
            type="text"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            autoComplete="username"
            placeholder="platform-admin"
            required
            autoFocus
          />
        </Field>

        <PasswordField
          id="admin-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="current-password"
          placeholder="Your administrator password"
          required
        />

        <button type="submit" className="btn btn--dark btn--lg btn--block" disabled={busy}>
          {busy && <span className="spinner" />}
          {busy ? 'Signing in…' : 'Open the administration'}
        </button>
      </form>
    </AuthLayout>
  )
}
