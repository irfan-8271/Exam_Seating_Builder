/** The small building blocks every page is made of. */
import { useEffect, useRef, useState } from 'react'
import { fetchBlobUrl } from '../api/client'
import { IconInfo } from './Icons'

/** A titled panel. */
export function Card({ title, actions, children, flush, subtitle, accent }) {
  const cls = accent ? `card card--accent-${accent}` : 'card'
  return (
    <section className={cls}>
      {(title || actions) && (
        <header className="card__head">
          <div className="spread">
            {title && <h2 className="card__title-row">{title}</h2>}
            {subtitle && <p className="small muted" style={{ margin: 0 }}>{subtitle}</p>}
          </div>
          {actions && <div className="card__actions">{actions}</div>}
        </header>
      )}
      <div className={flush ? 'card__body card__body--flush' : 'card__body'}>{children}</div>
    </section>
  )
}

/** One number with an optional accent colour and progress meter. */
export function Stat({ label, value, hint, limit, unit = '', accent = 'brand', icon }) {
  const numVal = typeof value === 'number' ? value : Number.NaN
  const numLim = typeof limit === 'number' ? limit : Number.NaN
  const ratio = !Number.isNaN(numVal) && !Number.isNaN(numLim) && numLim > 0
    ? Math.min(1, numVal / numLim)
    : null
  const tone = ratio === null ? '' : ratio >= 0.9 ? ' meter__fill--danger' : ratio >= 0.7 ? ' meter__fill--warn' : ''
  return (
    <div className={`stat stat--${accent}`}>
      <div className="stat__top">
        <div className="stat__label">{label}</div>
        {icon && <div className="stat__icon">{icon}</div>}
      </div>
      <div className="stat__value">
        {value}
        {limit ? (
          <span className="faint" style={{ fontSize: 15, fontWeight: 500 }}>
            {' '}/ {limit}{unit}
          </span>
        ) : null}
      </div>
      {hint && <div className="stat__hint">{hint}</div>}
      {ratio !== null && (
        <div className="meter">
          <div className={'meter__fill' + tone} style={{ width: `${ratio * 100}%` }} />
        </div>
      )}
    </div>
  )
}

/** A coloured tag. */
export function Badge({ children, tone = 'default' }) {
  const suffix = tone === 'default' ? '' : ` badge--${tone}`
  return <span className={'badge' + suffix}>{children}</span>
}

/** SaaS status pill with live indicator dot (PENDING, APPROVED, REJECTED, ACTIVE, SUSPENDED). */
export function StatusBadge({ status }) {
  const norm = (status || '').toUpperCase()
  let tone = 'default'
  let label = status || 'Unknown'
  if (norm === 'PENDING') {
    tone = 'warning'
    label = 'Pending Verification'
  } else if (norm === 'APPROVED' || norm === 'ACTIVE') {
    tone = 'success'
    label = norm === 'APPROVED' ? 'Approved' : 'Active'
  } else if (norm === 'REJECTED' || norm === 'SUSPENDED' || norm === 'CANCELED') {
    tone = 'danger'
    label = norm === 'REJECTED' ? 'Rejected' : norm === 'SUSPENDED' ? 'Suspended' : 'Canceled'
  }
  return (
    <span className={`badge badge--${tone} badge--dot`}>
      <span className="badge__dot" />
      {label}
    </span>
  )
}

/** Interactive contextual information icon & popover (works on desktop hover/click & mobile tap). */
export function InfoTip({ title, children }) {
  const [open, setOpen] = useState(false)
  const wrapRef = useRef(null)

  useEffect(() => {
    if (!open) return undefined
    function handleOutside(event) {
      if (wrapRef.current && !wrapRef.current.contains(event.target)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleOutside)
    document.addEventListener('touchstart', handleOutside)
    return () => {
      document.removeEventListener('mousedown', handleOutside)
      document.removeEventListener('touchstart', handleOutside)
    }
  }, [open])

  return (
    <span className="infotip" ref={wrapRef}>
      <button
        type="button"
        className={`infotip__btn ${open ? 'infotip__btn--open' : ''}`}
        aria-label={title || 'More information'}
        aria-expanded={open}
        onClick={(e) => {
          e.preventDefault()
          e.stopPropagation()
          setOpen((v) => !v)
        }}
      >
        <IconInfo width={15} height={15} />
      </button>
      {open && (
        <div className="infotip__popover" role="region" aria-label={title || 'Explanation'}>
          {title && <div className="infotip__title">{title}</div>}
          <div className="infotip__body">{children}</div>
        </div>
      )}
    </span>
  )
}

/** Loads and displays a protected image (QR code or payment screenshot) using Bearer auth. */
export function AuthImage({ path, alt = 'Image', className = '', refreshKey = 0, fallback = null }) {
  const [src, setSrc] = useState(null)
  const [failed, setFailed] = useState(false)
  const [loading, setLoading] = useState(Boolean(path))

  useEffect(() => {
    let active = true
    let objectUrl = null
    if (!path) {
      setSrc(null)
      setLoading(false)
      return undefined
    }
    setLoading(true)
    setFailed(false)
    fetchBlobUrl(path)
      .then((url) => {
        if (!active) {
          window.URL.revokeObjectURL(url)
          return
        }
        objectUrl = url
        setSrc(url)
      })
      .catch(() => {
        if (active) setFailed(true)
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    return () => {
      active = false
      if (objectUrl) {
        window.URL.revokeObjectURL(objectUrl)
      }
    }
  }, [path, refreshKey])

  if (loading) {
    return (
      <div className="auth-img-placeholder">
        <span className="spinner spinner--dark" />
        <span className="small muted">Loading image…</span>
      </div>
    )
  }
  if (failed || !src) {
    return fallback || <div className="auth-img-placeholder small muted">Image unavailable</div>
  }
  return <img src={src} alt={alt} className={className} />
}

/** A message the user should read. */
export function Alert({ tone = 'info', title, children, actions }) {
  return (
    <div className={`alert alert--${tone}`}>
      <div className="alert__content">
        {title && <strong className="alert__title">{title} </strong>}
        <div className="alert__body">{children}</div>
      </div>
      {actions && <div className="alert__actions">{actions}</div>}
    </div>
  )
}

/** A list of messages (for example the rows an import rejected). */
export function AlertList({ tone = 'error', title, items }) {
  if (!items || items.length === 0) return null
  return (
    <div className={`alert alert--${tone}`}>
      <div className="alert__content">
        {title && <strong className="alert__title">{title} </strong>}
        <ul className="alert__list">
          {items.map((item, index) => (
            <li key={index}>{item}</li>
          ))}
        </ul>
      </div>
    </div>
  )
}

/** While something is loading. */
export function Loading({ label = 'Loading…' }) {
  return (
    <div className="loading">
      <span className="spinner spinner--dark" />
      {label}
    </div>
  )
}

/** When there is nothing to show yet. */
export function EmptyState({ title, children, action }) {
  return (
    <div className="empty">
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {action}
    </div>
  )
}

/** A labelled input with optional contextual InfoTip. */
export function Field({ label, hint, info, style, children }) {
  return (
    <div className="field" style={style}>
      {label && (
        <div className="field__label-row">
          <label className="field__label">{label}</label>
          {info}
        </div>
      )}
      {children}
      {hint && <div className="field__hint">{hint}</div>}
    </div>
  )
}

/** A dialog. */
export function Modal({ title, children, footer, onClose, wide }) {
  useEffect(() => {
    const onKey = (event) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div
      className="modal__backdrop"
      onMouseDown={(event) => event.target === event.currentTarget && onClose()}
    >
      <div className={wide ? 'modal modal--wide' : 'modal'} role="dialog" aria-modal="true">
        <header className="modal__head">
          <h2>{title}</h2>
          <button type="button" className="icon-button" onClick={onClose} aria-label="Close">
            ×
          </button>
        </header>
        <div className="modal__body">{children}</div>
        {footer && <footer className="modal__foot">{footer}</footer>}
      </div>
    </div>
  )
}

/** A responsive table that automatically stacks into labelled cards on narrow screens. */
export function Table({ columns, rows, empty, onRowClick }) {
  if (!rows || rows.length === 0) {
    return <div className="table__empty">{empty || 'Nothing here yet.'}</div>
  }
  return (
    <div className="table-wrap table-wrap--responsive">
      <table>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.key} className={column.numeric ? 'numeric' : ''}>
                {column.title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr
              key={row.id ?? row.code ?? index}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              style={onRowClick ? { cursor: 'pointer' } : undefined}
            >
              {columns.map((column) => (
                <td
                  key={column.key}
                  data-label={column.title || ''}
                  className={
                    (column.numeric ? 'numeric ' : '') +
                    (!column.title ? 'td--actions' : '')
                  }
                >
                  <div className="td__cell-val">
                    {column.render ? column.render(row) : row[column.key]}
                  </div>
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Formats a byte count. */
export function bytes(value) {
  if (!value) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = value
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024
    unit += 1
  }
  return `${size.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`
}

/** Formats an instant as a readable date. */
export function moment(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
