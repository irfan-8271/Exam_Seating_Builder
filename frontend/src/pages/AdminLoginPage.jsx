import { useEffect, useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import AuthLayout, { PasswordField } from '../components/AuthLayout'
import { Alert, Field } from '../components/ui'
import { useAuth } from '../auth/AuthContext'
import { adminApi } from '../api/endpoints'
import { IconAlert, IconLock } from '../components/Icons'

/**
 * Dedicated platform administrator sign-in screen.
 *
 * Authenticates exclusively against POST /api/v1/auth/platform/login and never
 * exposes internal administrator usernames or tenant metrics to unauthenticated
 * visitors.
 */
export default function AdminLoginPage() {
  const { platformSignIn, user, isPlatformAdmin } = useAuth()
  const navigate = useNavigate()

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState(null)

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
      title="Platform Administration"
      lead="Sign in to the SaaS operator console."
      asideTitle="Centralized SaaS operations & payment verification"
      asideLead="Monitor registered institutions, verify subscription upgrade payments, manage custom capacity overrides, and audit platform-wide activity from one console."
      points={[
        'Review & verify pending UPI / bank-transfer upgrade requests',
        'Configure payment QR codes, UPI IDs, and verification policies',
        'Monitor institution quotas, active plans, and custom overrides',
        'Real-time platform activity log and account governance',
      ]}
      footer={
        <Link to="/signin">← Back to Institution Sign-in</Link>
      }
    >
      {status && !status.enabled && (
        <div className="admin-note">
          <IconAlert width={16} height={16} />
          <span>
            Platform administrator access is not currently enabled on this deployment.
            Contact the system operator to provision administrator credentials.
          </span>
        </div>
      )}

      {(!status || status.enabled) && (
        <div className="admin-note">
          <IconLock width={16} height={16} />
          <span>
            Restricted to authorized platform administrators. College and school accounts
            should use the <Link to="/signin">Institution Sign-in</Link>.
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
            placeholder="Enter administrator username"
            required
            autoFocus
          />
        </Field>

        <PasswordField
          id="admin-password"
          label="Password"
          value={password}
          onChange={setPassword}
          autoComplete="current-password"
          placeholder="Enter administrator password"
        />

        <button
          type="submit"
          className="btn btn--primary btn--block btn--lg"
          style={{ marginTop: 8 }}
          disabled={busy || (status && !status.enabled)}
        >
          {busy && <span className="spinner" />}
          {busy ? 'Verifying…' : 'Open Admin Console'}
        </button>
      </form>
    </AuthLayout>
  )
}
