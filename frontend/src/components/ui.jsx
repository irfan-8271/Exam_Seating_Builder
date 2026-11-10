/** The small building blocks every page is made of. */
import { useEffect } from 'react'

/** A titled panel. */
export function Card({ title, actions, children, flush, subtitle }) {
  return (
    <section className="card">
      {(title || actions) && (
        <header className="card__head">
          <div className="spread">
            {title && <h2>{title}</h2>}
            {subtitle && <p className="small muted" style={{ margin: 0 }}>{subtitle}</p>}
          </div>
          {actions}
        </header>
      )}
      <div className={flush ? 'card__body card__body--flush' : 'card__body'}>{children}</div>
    </section>
  )
}

/** One number with a meter under it. */
export function Stat({ label, value, hint, limit, unit = '' }) {
  const ratio = limit ? Math.min(1, value / limit) : null
  const tone = ratio === null ? '' : ratio >= 0.9 ? ' meter__fill--danger' : ratio >= 0.7 ? ' meter__fill--warn' : ''
  return (
    <div className="stat">
      <div className="stat__label">{label}</div>
      <div className="stat__value">
        {value}
        {limit ? <span className="faint" style={{ fontSize: 15, fontWeight: 500 }}> / {limit}{unit}</span> : null}
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

/** A message the user should read. */
export function Alert({ tone = 'info', title, children }) {
  return (
    <div className={`alert alert--${tone}`}>
      {title && <strong>{title} </strong>}
      {children}
    </div>
  )
}

/** A list of messages (for example the rows an import rejected). */
export function AlertList({ tone = 'error', title, items }) {
  if (!items || items.length === 0) return null
  return (
    <div className={`alert alert--${tone}`}>
      {title && <strong>{title} </strong>}
      <ul className="alert__list">
        {items.map((item, index) => (
          <li key={index}>{item}</li>
        ))}
      </ul>
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

/** A labelled input. */
export function Field({ label, hint, children }) {
  return (
    <div className="field">
      {label && <label className="field__label">{label}</label>}
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
    <div className="modal__backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
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

/** A table with an empty state. */
export function Table({ columns, rows, empty, onRowClick }) {
  if (!rows || rows.length === 0) {
    return <div className="table__empty">{empty || 'Nothing here yet.'}</div>
  }
  return (
    <div className="table-wrap">
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
              key={row.id ?? index}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              style={onRowClick ? { cursor: 'pointer' } : undefined}
            >
              {columns.map((column) => (
                <td key={column.key} className={column.numeric ? 'numeric' : ''}>
                  {column.render ? column.render(row) : row[column.key]}
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
