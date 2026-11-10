import { useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import AuthLayout, { PasswordField, PasswordStrength } from '../components/AuthLayout'
import { Alert, Field } from '../components/ui'
import { useAuth } from '../auth/AuthContext'

/**
 * Creating an account.
 *
 * <p>Nobody approves this and no administrator password is involved: the
 * person chooses their own login name and their own password, and a
 * workspace is created for them. They are its administrator, and they may
 * invite their colleagues afterwards.</p>
 */
export default function RegisterPage() {
  const { register, user } = useAuth()
  const navigate = useNavigate()

  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [institutionName, setInstitutionName] = useState('')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  if (user) {
    return <Navigate to="/dashboard" replace />
  }

  async function submit(event) {
    event.preventDefault()
    setError(null)

    if (password !== confirm) {
      setError('The two passwords do not match.')
      return
    }

    setBusy(true)
    try {
      await register({
        fullName: fullName.trim(),
        email: email.trim(),
        institutionName: institutionName.trim(),
        username: username.trim(),
        password,
      })
      navigate('/dashboard', { replace: true })
    } catch (failure) {
      setError(failure.message || 'The account could not be created')
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      title="Create your account"
      lead="Two minutes, and you have a workspace of your own."
      asideTitle="Bring your own roster — no template needed"
      asideLead="The first thing you will do is upload the spreadsheet your office already keeps. The system reads the headings and asks which column identifies a student."
      points={[
        'No administrator password, no approval, no waiting',
        'Your username and your password — nobody else knows them',
        'The workspace is created for you and named after you',
        'Invite the exam cell and the invigilators when you need to',
      ]}
      footer={
        <>
          Already have an account? <Link to="/signin">Sign in</Link>
        </>
      }
    >
      {error && <Alert tone="error">{error}</Alert>}

      <form onSubmit={submit} noValidate>
        <Field label="Your name" hint="How the plan and the exports should address you.">
          <input
            type="text"
            value={fullName}
            onChange={(event) => setFullName(event.target.value)}
            autoComplete="name"
            placeholder="Dr A. Sharma"
          />
        </Field>

        <Field label="Email address" hint="Optional, but it is how we reach you about your account.">
          <input
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            autoComplete="email"
            placeholder="you@example.org"
          />
        </Field>

        <Field
          label="Institution or workspace name"
          hint="Optional — we will name it after you if you leave it empty."
        >
          <input
            type="text"
            value={institutionName}
            onChange={(event) => setInstitutionName(event.target.value)}
            placeholder="Springfield University"
          />
        </Field>

        <Field label="Username" hint="Letters, digits, dots, dashes and underscores.">
          <input
            type="text"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            autoComplete="username"
            placeholder="a.sharma"
            required
            minLength={3}
            maxLength={50}
          />
        </Field>

        <PasswordField
          id="password"
          label="Password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="new-password"
          hint="At least 8 characters."
          required
          minLength={8}
        />
        <PasswordStrength value={password} />

        <PasswordField
          id="confirm-password"
          label="Confirm the password"
          value={confirm}
          onChange={(event) => setConfirm(event.target.value)}
          autoComplete="new-password"
          required
        />

        <button type="submit" className="btn btn--primary btn--lg btn--block" disabled={busy}>
          {busy && <span className="spinner" />}
          {busy ? 'Creating your workspace…' : 'Create the account'}
        </button>
      </form>
    </AuthLayout>
  )
}
