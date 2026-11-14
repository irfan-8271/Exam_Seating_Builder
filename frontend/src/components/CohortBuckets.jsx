import { useCallback, useEffect, useMemo, useState } from 'react'
import { cohortApi } from '../api/endpoints'
import { Alert, Badge, EmptyState, Field, Loading, Modal } from './ui'

/**
 * Flat classmate bucket list with expand/collapse and a per-bucket
 * "Configure cohort" action.
 *
 * Buckets are always derived from the live roster, so configuring a cohort
 * inside a bucket simply re-derives which flat bucket each student belongs to.
 * No student record is ever modified, lost, or duplicated by that action.
 */
export function CohortBuckets({
  overview,
  loading,
  onRefresh,
  onDeleted,
  renderMembers,
  titleMap = new Map(),
  showActions = true,
}) {
  const [expanded, setExpanded] = useState(() => new Set())
  const [configuring, setConfiguring] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)

  const buckets = overview?.buckets || []

  const toggle = useCallback((label) => {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(label)) next.delete(label)
      else next.add(label)
      return next
    })
  }, [])

  async function deleteBucket(bucket) {
    const ok = window.confirm(
      `Delete all ${bucket.count} student(s) in "${bucket.label}"?\n\n` +
        'This also removes any seating allocations for these students. This cannot be undone.',
    )
    if (!ok) return
    setBusy(true)
    setError(null)
    try {
      const result = await cohortApi.remove({
        attributes: overview.attributes,
        values: bucket.values,
      })
      setNotice(`Removed ${result?.deleted ?? bucket.count} student(s) from "${bucket.label}".`)
      setExpanded((prev) => {
        const next = new Set(prev)
        next.delete(bucket.label)
        return next
      })
      await onRefresh?.()
      onDeleted?.()
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  async function removeRefinement(bucket) {
    setBusy(true)
    setError(null)
    try {
      await cohortApi.removeRule({
        attributes: overview.attributes,
        values: bucket.values,
      })
      setNotice(`"${bucket.label}" is no longer split further.`)
      await onRefresh?.()
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <Loading label="Loading categories…" />
  if (!buckets.length) {
    return (
      <EmptyState title="No categories yet">
        Upload a roster and choose the attributes that define classmates — categories appear here
        automatically.
      </EmptyState>
    )
  }

  const misc = buckets.find((b) => b.isMiscellaneous)
  const regular = buckets.filter((b) => !b.isMiscellaneous)

  const renderBucket = (bucket) => {
    const isOpen = expanded.has(bucket.label)
    return (
      <div key={bucket.label} className="cohort-bucket">
        <div className="cohort-bucket__head">
          <button
            type="button"
            className="cohort-bucket__toggle"
            onClick={() => toggle(bucket.label)}
            aria-expanded={isOpen}
          >
            <span className="cohort-bucket__chevron" aria-hidden="true">
              {isOpen ? '▾' : '▸'}
            </span>
            <span className="cohort-bucket__label">{bucket.label}</span>
            <Badge tone={bucket.isMiscellaneous ? 'warning' : 'info'}>
              {bucket.count} student{bucket.count === 1 ? '' : 's'}
            </Badge>
            {bucket.refined && bucket.groupAttribute && (
              <span className="small muted">
                split by {titleMap.get(bucket.groupAttribute) || bucket.groupAttribute}
              </span>
            )}
          </button>
          {showActions && (
            <div className="btn-row">
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => setConfiguring(bucket)}
              >
                Configure cohort
              </button>
              {bucket.refined && (
                <button
                  type="button"
                  className="btn btn--sm"
                  onClick={() => removeRefinement(bucket)}
                  disabled={busy}
                >
                  Undo split
                </button>
              )}
              <button
                type="button"
                className="btn btn--sm btn--danger"
                onClick={() => deleteBucket(bucket)}
                disabled={busy}
              >
                Delete
              </button>
            </div>
          )}
        </div>
        {bucket.isMiscellaneous && bucket.missing?.length > 0 && (
          <div className="cohort-bucket__note">
            <strong>Needs attention:</strong> these students have no value for{' '}
            {bucket.missing.map((key) => titleMap.get(key) || key).join(', ')}. Edit the student to add
            it and they will move into the right category automatically.
          </div>
        )}
        {isOpen && renderMembers && <div className="cohort-bucket__body">{renderMembers(bucket)}</div>}
      </div>
    )
  }

  return (
    <div className="cohort-buckets">
      {error && <Alert tone="error">{error}</Alert>}
      {notice && <Alert tone="success">{notice}</Alert>}
      {regular.map(renderBucket)}
      {misc && renderBucket(misc)}
      {configuring && (
        <ConfigureCohortDialog
          bucket={configuring}
          attributes={overview.attributes}
          candidates={overview.candidates || []}
          titleMap={titleMap}
          onClose={() => setConfiguring(null)}
          onApplied={async () => {
            setConfiguring(null)
            await onRefresh?.()
          }}
        />
      )}
    </div>
  )
}

/**
 * Dialog for splitting one bucket into new flat buckets by choosing an extra
 * attribute. Shows a preview of the resulting buckets before applying.
 */
function ConfigureCohortDialog({ bucket, attributes, candidates, titleMap, onClose, onApplied }) {
  const [attribute, setAttribute] = useState('')
  const [preview, setPreview] = useState(null)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    if (!attribute) {
      setPreview(null)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    cohortApi
      .preview({ attributes, values: bucket.values, groupAttribute: attribute })
      .then((result) => {
        if (!cancelled) setPreview(result)
      })
      .catch((failure) => {
        if (!cancelled) setError(failure.message)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [attribute, attributes, bucket.values])

  async function apply() {
    setBusy(true)
    setError(null)
    try {
      await cohortApi.configure({ attributes, values: bucket.values, groupAttribute: attribute })
      await onApplied()
    } catch (failure) {
      setError(failure.message)
      setBusy(false)
    }
  }

  return (
    <Modal
      title={`Configure cohort — ${bucket.label}`}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn--primary"
            onClick={apply}
            disabled={busy || loading || !attribute || !preview?.length}
          >
            {busy ? 'Applying…' : 'Apply — split into new categories'}
          </button>
        </>
      }
    >
      <div className="small muted" style={{ marginBottom: 12 }}>
        Choose one more attribute. <strong>{bucket.label}</strong> will be replaced by new flat
        categories. Students without a value for the chosen attribute stay where they are.
      </div>

      <Field label="Split this category by">
        <select value={attribute} onChange={(e) => setAttribute(e.target.value)}>
          <option value="">Select an attribute…</option>
          {candidates.map((key) => (
            <option key={key} value={key}>
              {titleMap.get(key) || key}
            </option>
          ))}
        </select>
      </Field>

      {error && (
        <div style={{ marginTop: 12 }}>
          <Alert tone="error">{error}</Alert>
        </div>
      )}

      {loading && (
        <div style={{ marginTop: 12 }}>
          <Loading label="Working out the new categories…" />
        </div>
      )}

      {!loading && preview && (
        <div style={{ marginTop: 16 }}>
          <div className="small" style={{ marginBottom: 6 }}>
            <strong>{preview.length}</strong> new categor{preview.length === 1 ? 'y' : 'ies'} will be
            created:
          </div>
          <div className="cohort-preview">
            {preview.map((item) => (
              <div key={item.label} className="cohort-preview__row">
                <span>{item.label}</span>
                <Badge tone={item.isMiscellaneous ? 'warning' : 'info'}>
                  {item.count} student{item.count === 1 ? '' : 's'}
                </Badge>
              </div>
            ))}
          </div>
        </div>
      )}
    </Modal>
  )
}
