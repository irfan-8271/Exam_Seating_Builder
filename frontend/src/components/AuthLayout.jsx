import { useMemo, useState } from 'react'
import Brand from './Brand'
import { IconCheckCircle, IconEye, IconEyeOff } from './Icons'

/**
 * The frame every sign-in screen sits in: a dark panel that explains what the
 * product is on the left, the form on the right. On a phone the panel goes
 * away and the form takes the whole screen.
 */
export default function AuthLayout({
  title,
  lead,
  children,
  footer,
  asideTitle,
  asideLead,
  points = [],
  admin = false,
}) {
  return (
    <div className={admin ? 'auth auth--admin' : 'auth'}>
      <aside className="auth__aside">
        <Brand />

        <div className="auth__pitch">
          <h2>{asideTitle}</h2>
          <p>{asideLead}</p>
          <ul className="auth__points">
            {points.map((point) => (
              <li key={point}>
                <IconCheckCircle width={17} height={17} />
                {point}
              </li>
            ))}
          </ul>
        </div>

        <p className="small" style={{ color: 'rgba(226,230,244,0.45)', margin: 0 }}>
          © {new Date().getFullYear()} Exam Seating Builder
        </p>
      </aside>

      <main className="auth__main">
        <div className="auth__panel">
          <header className="auth__head">
            <h1>{title}</h1>
            {lead && <p>{lead}</p>}
          </header>
          {children}
          {footer && <div className="auth__foot">{footer}</div>}
        </div>
      </main>
    </div>
  )
}

/** A password box with a button that shows the password. */
export function PasswordField({
  value,
  onChange,
  label = 'Password',
  hint,
  id = 'password',
  autoComplete = 'current-password',
  ...rest
}) {
  const [visible, setVisible] = useState(false)
  return (
    <div className="field">
      {label && (
        <label className="field__label" htmlFor={id}>
          {label}
        </label>
      )}
      <div className="password-field">
        <input
          id={id}
          type={visible ? 'text' : 'password'}
          value={value}
          onChange={onChange}
          autoComplete={autoComplete}
          {...rest}
        />
        <button
          type="button"
          onClick={() => setVisible((current) => !current)}
          aria-label={visible ? 'Hide the password' : 'Show the password'}
          tabIndex={-1}
        >
          {visible ? <IconEyeOff width={17} height={17} /> : <IconEye width={17} height={17} />}
        </button>
      </div>
      {hint && <div className="field__hint">{hint}</div>}
    </div>
  )
}

/**
 * A four-step strength meter. It says nothing about what is wrong with a
 * password — only how much of one it is.
 */
export function PasswordStrength({ value }) {
  const score = useMemo(() => {
    let points = 0
    if (value.length >= 8) points += 1
    if (value.length >= 12) points += 1
    if (/[a-z]/.test(value) && /[A-Z]/.test(value)) points += 1
    if (/\d/.test(value) && /[^A-Za-z0-9]/.test(value)) points += 1
    return points
  }, [value])

  if (!value) return null

  const labels = ['Too short', 'Weak', 'Fair', 'Good', 'Strong']
  const tones = ['', 'weak', 'fair', 'good', 'strong']

  return (
    <>
      <div className={`strength strength--${tones[score]}`}>
        <span />
        <span />
        <span />
        <span />
      </div>
      <div className="strength__label">{labels[score]}</div>
    </>
  )
}
