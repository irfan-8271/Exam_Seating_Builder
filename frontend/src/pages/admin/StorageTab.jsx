import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Badge, Card, EmptyState, Field, bytes, moment } from '../../components/ui'
import { adminApi } from '../../api/endpoints'

/**
 * What the database is holding, and how to reclaim space.
 *
 * <p>Documents and images live in the database rather than on the server's
 * disk, so a busy platform accumulates bytes inside the database itself. On a
 * small hosted database — a free tier with a 1 GB disk, for instance — that
 * becomes the limiting factor.
 *
 * <p>Two things can be removed, and one deliberately cannot:</p>
 *
 * <ul>
 *   <li><strong>Uploaded files</strong> — the working copy of a roster. Its
 *       contents have already been imported into the roster tables, so
 *       removing the file does not remove the students.</li>
 *   <li><strong>Screenshots on settled requests</strong> — the payment proof
 *       attached to a request that has already been approved or rejected.</li>
 *   <li><strong>Screenshots still awaiting review</strong> — never. They are
 *       the evidence used to approve or reject the request.</li>
 * </ul>
 *
 * <p>Every removal is two steps: a preview that says exactly what would go,
 * then a confirmation that actually does it. Nothing is deleted by accident.
 * </p>
 */

const AGE_OPTIONS = [
  { value: 0, label: 'Everything' },
  { value: 7, label: 'Older than 7 days' },
  { value: 30, label: 'Older than 30 days' },
  { value: 90, label: 'Older than 90 days' },
  { value: 180, label: 'Older than 180 days' },
  { value: 365, label: 'Older than a year' },
]

/** One cleanup, ready to be previewed and then confirmed. */
function CleanupPanel({
  title,
  subtitle,
  description,
  scope,
  preview,
  onPreview,
  onConfirm,
  busy,
  lastResult,
}) {
  const [age, setAge] = useState(30)

  const run = async () => {
    const result = await onPreview({ olderThanDays: age })
    if (result) setPreview(result)
  }

  return (
    <Card title={title} subtitle={subtitle}>
      <p className="small muted" style={{ marginTop: 0 }}>
        {description}
      </p>

      <div className="row" style={{ gap: 12, alignItems: 'flex-end', marginBottom: 14 }}>
        <Field label="Which files" style={{ maxWidth: 220 }}>
          <select value={age} onChange={(event) => setAge(Number(event.target.value))}>
            {AGE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </Field>
        <button className="btn btn--sm" onClick={run} disabled={busy}>
          {busy ? 'Checking…' : 'Preview what would be removed'}
        </button>
      </div>

      {preview && (
        <Alert tone={preview.itemCount > 0 ? 'warning' : 'info'} title={`Preview — ${scope}`}>
          {preview.itemCount === 0 ? (
            <p style={{ margin: 0 }}>
              Nothing matches this selection, so there is nothing to remove.
            </p>
          ) : (
            <>
              <p style={{ margin: '0 0 6px' }}>
                <strong>
                  {preview.itemCount} item{preview.itemCount === 1 ? '' : 's'}
                </strong>{' '}
                totalling <strong>{bytes(preview.bytes)}</strong> would be removed, across{' '}
                {preview.institutions} workspace{preview.institutions === 1 ? '' : 's'}.
              </p>
              {preview.wouldDeleteSample?.length > 0 && (
                <ul className="small muted" style={{ margin: '0 0 10px', paddingLeft: 18 }}>
                  {preview.wouldDeleteSample.map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              )}
              <button
                className="btn btn--sm btn--danger"
                onClick={() => onConfirm({ olderThanDays: age, confirm: true })}
                disabled={busy}
              >
                {busy ? 'Removing…' : `Yes, remove ${preview.itemCount} item${preview.itemCount === 1 ? '' : 's'}`}
              </button>
            </>
          )}
        </Alert>
      )}

      {lastResult && (
        <div style={{ marginTop: 12 }}>
          <Alert tone="success" title="Done">
            <p style={{ margin: 0 }}>{lastResult.message}</p>
          </Alert>
        </div>
      )}
    </Card>
  )
}

export default function StorageTab() {
  const [usage, setUsage] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const [filePreview, setFilePreview] = useState(null)
  const [shotPreview, setShotPreview] = useState(null)
  const [fileResult, setFileResult] = useState(null)
  const [shotResult, setShotResult] = useState(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      const data = await adminApi.storage()
      setUsage(data)
    } catch (failure) {
      setError(failure.message || 'Could not read the storage usage.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const categories = useMemo(() => usage?.categories || [], [usage])

  const withAction = useCallback(async (work, after) => {
    setBusy(true)
    setError(null)
    try {
      const result = await work()
      after(result)
      await load()
    } catch (failure) {
      setError(failure.message || 'The cleanup could not be completed.')
    } finally {
      setBusy(false)
    }
  }, [load])

  if (loading) {
    return <p className="muted">Reading the storage usage…</p>
  }

  return (
    <div className="stack">
      {error && (
        <Alert tone="error" title="Something went wrong">
          <p style={{ margin: 0 }}>{error}</p>
        </Alert>
      )}

      <Card
        title="Database storage"
        subtitle="What the database is holding, and how much of it could be reclaimed."
      >
        <div className="grid grid--3" style={{ marginBottom: 16 }}>
          <div className="stat">
            <span className="stat__label">Total held</span>
            <span className="stat__value">{bytes(usage?.totalBytes)}</span>
          </div>
          <div className="stat">
            <span className="stat__label">Reclaimable</span>
            <span className="stat__value">{bytes(usage?.reclaimableBytes)}</span>
          </div>
          <div className="stat">
            <span className="stat__label">Protected</span>
            <span className="stat__value">{bytes(usage?.protectedBytes)}</span>
          </div>
        </div>

        <div className="stack" style={{ gap: 10 }}>
          {categories.map((category) => (
            <div
              key={category.category}
              className="row row--between"
              style={{ padding: '12px 14px', background: 'var(--surface-2)', borderRadius: 10 }}
            >
              <div style={{ minWidth: 0 }}>
                <div className="strong">{category.label}</div>
                <div className="small muted">{category.description}</div>
              </div>
              <div style={{ textAlign: 'right', flexShrink: 0, marginLeft: 12 }}>
                <div className="strong">{bytes(category.totalBytes)}</div>
                <div className="small muted">
                  {category.itemCount} item{category.itemCount === 1 ? '' : 's'}
                  {category.reclaimableCount > 0 && (
                    <>
                      {' '}
                      · <Badge tone="warning">{category.reclaimableCount} removable</Badge>
                    </>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>

        {usage?.notes?.length > 0 && (
          <div style={{ marginTop: 14 }}>
            <Alert tone="info" title="Before you clean up">
              <ul style={{ margin: 0, paddingLeft: 18 }}>
                {usage.notes.map((note) => (
                  <li key={note} style={{ marginBottom: 4 }}>
                    {note}
                  </li>
                ))}
              </ul>
            </Alert>
          </div>
        )}
      </Card>

      {categories.length > 0 && categories.some((c) => c.reclaimableCount > 0) ? (
        <div className="grid grid--2">
          <CleanupPanel
            title="Remove uploaded files"
            subtitle="Rosters and documents uploaded by workspaces."
            description="These are working copies — the students they carried have already been imported into the roster tables, so removing the file does not remove any student."
            scope="uploaded files"
            preview={filePreview}
            onPreview={(body) =>
              withAction(async () => {
                const result = await adminApi.previewStoredFileCleanup(body)
                setFilePreview(result)
                return result
              })
            }
            onConfirm={(body) =>
              withAction(async () => {
                const result = await adminApi.cleanupStoredFiles(body)
                setFileResult(result)
                setFilePreview(null)
                return result
              })
            }
            busy={busy}
            lastResult={fileResult}
          />

          <CleanupPanel
            title="Remove screenshots from settled requests"
            subtitle="Payment proofs on requests that were already approved or rejected."
            description="The screenshot goes; the request, its amounts and its decision stay, so the upgrade history remains intact. A request that is still waiting for a decision is never touched — its screenshot is the evidence you need to decide it."
            scope="settled request screenshots"
            preview={shotPreview}
            onPreview={(body) =>
              withAction(async () => {
                const result = await adminApi.previewScreenshotCleanup(body)
                setShotPreview(result)
                return result
              })
            }
            onConfirm={(body) =>
              withAction(async () => {
                const result = await adminApi.cleanupScreenshots(body)
                setShotResult(result)
                setShotPreview(null)
                return result
              })
            }
            busy={busy}
            lastResult={shotResult}
          />
        </div>
      ) : (
        <EmptyState title="Nothing to clean up">
          Every stored byte is either still needed or protected as evidence. New uploads will show
          up here as they arrive.
        </EmptyState>
      )}
    </div>
  )
}
