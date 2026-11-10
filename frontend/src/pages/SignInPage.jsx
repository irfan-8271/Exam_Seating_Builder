import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams, Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { Alert, Field } from '../components/ui'

/**
 * The sign-in screen.
 *
 * The same form serves everybody: an institution account lands on the
 * dashboard, a platform administrator lands on the admin screen. The link at
 * the bottom of the page is the way in for the operator — it is deliberately
 * small, so it does not distract the ordinary user.
 */
export default function SignInPage() {
  const { signIn, register } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const adminMode = params.get('admin') === '1'

  const [mode, setMode] = useState(adminMode ? 'admin' : 'signin')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [fullName, setFullName] = useState('')
  const [institutionName, setInstitutionName] = useState('')
  const [institutionEmail, setInstitutionEmail] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  // The administrator tab follows the address bar: opening
  // /signin?admin=1 switches the form over, even when the page was already
  // open.
  useEffect(() => {
    setMode(adminMode ? 'admin' : 'signin')
  }, [adminMode])

  async function submit(event) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      if (mode === 'register') {
        const session = await register({
          username: username.trim(),
          password,
          fullName: fullName.trim() || undefined,
          institutionName: institutionName.trim() || undefined,
          contactEmail: institutionEmail.trim() || undefined,
        })
        navigate(session.user?.role === 'PLATFORM_ADMIN' ? '/admin' : '/')
        return
      }
      const session = await signIn(username.trim(), password)
      navigate(session.user?.role === 'PLATFORM_ADMIN' ? '/admin' : '/')
    } catch (failure) {
      setError(failure.message || 'Sign in failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth">
      <div className="auth__box">
        <div className="auth__card">
          <div className="auth__brand">
            <h1>Exam Seating Builder</h1>
            <p>
              {mode === 'admin'
                ? 'Platform administration'
                : 'Build seating arrangements from your own roster'}
            </p>
          </div>

          <div className="auth__tabs">
            <button
              type="button"
              className={`auth__tab ${mode === 'signin' ? 'auth__tab--active' : ''}`}
              onClick={() => setMode('signin')}
            >
              Sign in
            </button>
            <button
              type="button"
              className={`auth__tab ${mode === 'register' ? 'auth__tab--active' : ''}`}
              onClick={() => setMode('register')}
            >
              Register
            </button>
            {adminMode && (
              <button
                type="button"
                className={`auth__tab ${mode === 'admin' ? 'auth__tab--active' : ''}`}
                onClick={() => setMode('admin')}
              >
                Administrator
              </button>
            )}
          </div>

          {error && <Alert tone="error">{error}</Alert>}

          <form onSubmit={submit}>
            {mode === 'register' && (
              <>
                <Field label="Institution name" hint="Your school, college or university.">
                  <input
                    type="text"
                    value={institutionName}
                    onChange={(event) => setInstitutionName(event.target.value)}
                    placeholder="Springfield University"
                  />
                </Field>
                <Field label="Contact email">
                  <input
                    type="email"
                    value={institutionEmail}
                    onChange={(event) => setInstitutionEmail(event.target.value)}
                    placeholder="exam-cell@example.org"
                  />
                </Field>
                <Field label="Your name">
                  <input
                    type="text"
                    value={fullName}
                    onChange={(event) => setFullName(event.target.value)}
                    placeholder="Dr. A. Sharma"
                  />
                </Field>
              </>
            )}

            <Field label={mode === 'register' ? 'Choose a username' : 'Username'}>
              <input
                type="text"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                autoComplete="username"
                required
                autoFocus
              />
            </Field>

            <Field label="Password">
              <input
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete={mode === 'register' ? 'new-password' : 'current-password'}
                required
              />
            </Field>

            <button
              type="submit"
              className="btn btn--primary"
              style={{ width: '100%' }}
              disabled={busy}
            >
              {busy && <span className="spinner" />}
              {mode === 'register' ? 'Create the institution' : 'Sign in'}
            </button>
          </form>
        </div>

        <div className="auth__foot">
          {mode === 'admin' ? (
            <Link to="/signin">← back to the normal sign-in</Link>
          ) : (
            /* A small, unobtrusive way in for the operator. */
            <Link to="/signin?admin=1" className="foot-link">
              platform administration
            </Link>
          )}
        </div>
      </div>
    </div>
  )
}
