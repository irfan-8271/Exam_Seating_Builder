import { Link } from 'react-router-dom'

/**
 * The mark: a seating chart seen from above — three rows of benches with one
 * student moved to the middle, which is the whole idea of the product.
 */
export function BrandMark({ size = 18 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="2.5" y="4" width="8" height="4.4" rx="1.1" fill="currentColor" opacity="0.55" />
      <rect x="13.5" y="4" width="8" height="4.4" rx="1.1" fill="currentColor" opacity="0.55" />
      <rect x="2.5" y="15.6" width="8" height="4.4" rx="1.1" fill="currentColor" opacity="0.55" />
      <rect x="13.5" y="15.6" width="8" height="4.4" rx="1.1" fill="currentColor" opacity="0.55" />
      <rect x="8" y="9.8" width="8" height="4.4" rx="1.1" fill="currentColor" />
    </svg>
  )
}

/** The mark with the name beside it. */
export default function Brand({ to = '/', tagline = null, onClick }) {
  const content = (
    <>
      <span className="brand__mark">
        <BrandMark size={19} />
      </span>
      <span className="brand__text">
        Exam Seating Builder
        {tagline && <span className="brand__label">{tagline}</span>}
      </span>
    </>
  )

  if (onClick) {
    return (
      <button type="button" className="brand" onClick={onClick} style={{ background: 'none', border: 0, padding: 0, cursor: 'pointer' }}>
        {content}
      </button>
    )
  }

  return (
    <Link className="brand" to={to}>
      {content}
    </Link>
  )
}
