import { useMemo, useState } from 'react'
import { adminApi } from '../../api/endpoints'
import {
  Alert,
  AuthImage,
  Badge,
  Card,
  EmptyState,
  Field,
  Modal,
  StatusBadge,
  Table,
  moment,
} from '../../components/ui'
import {
  IconCheck,
  IconCopy,
  IconEye,
  IconSearch,
  IconXCircle,
} from '../../components/Icons'

const STATUS_FILTERS = [
  { value: 'PENDING', label: 'Pending Verification' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'ALL', label: 'All Requests' },
]

export default function RequestsTab({ requests = [], institutions = [], onChanged }) {
  const [statusFilter, setStatusFilter] = useState('PENDING')
  const [search, setSearch] = useState('')
  const [inspecting, setInspecting] = useState(null)
  const [rejecting, setRejecting] = useState(null)
  const [rejectReason, setRejectReason] = useState('')
  const [adminNotes, setAdminNotes] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const [copiedId, setCopiedId] = useState(null)

  const instMap = useMemo(() => {
    const map = new Map()
    ;(institutions || []).forEach((i) => map.set(i.id, i))
    return map
  }, [institutions])

  const counts = useMemo(() => {
    const c = { PENDING: 0, APPROVED: 0, REJECTED: 0, ALL: requests.length }
    requests.forEach((r) => {
      if (c[r.status] !== undefined) c[r.status] += 1
    })
    return c
  }, [requests])

  const filtered = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return requests.filter((r) => {
      if (statusFilter !== 'ALL' && r.status !== statusFilter) return false
      if (!needle) return true
      return (
        (r.institutionName || '').toLowerCase().includes(needle) ||
        (r.requestedByUsername || '').toLowerCase().includes(needle) ||
        (r.transactionReference || '').toLowerCase().includes(needle) ||
        (r.requestedPlanCode || '').toLowerCase().includes(needle)
      )
    })
  }, [requests, statusFilter, search])

  async function copyTx(id, tx) {
    try {
      await navigator.clipboard.writeText(tx)
      setCopiedId(id)
      setTimeout(() => setCopiedId(null), 1800)
    } catch {
      setCopiedId(null)
    }
  }

  async function handleApprove(req, notes = '') {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      await adminApi.approveRequest(req.id, { adminNotes: notes || undefined })
      setInspecting(null)
      setAdminNotes('')
      setNotice(
        `Approved request #${req.id} for ${req.institutionName}. Subscription plan & limits are now active.`,
      )
      await onChanged()
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  async function handleRejectSubmit(event) {
    event.preventDefault()
    if (!rejecting) return
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      await adminApi.rejectRequest(rejecting.id, {
        reason: rejectReason.trim() || undefined,
        adminNotes: adminNotes.trim() || undefined,
      })
      const instName = rejecting.institutionName
      const reqId = rejecting.id
      setRejecting(null)
      setInspecting(null)
      setRejectReason('')
      setAdminNotes('')
      setNotice(`Rejected request #${reqId} (${instName}). Workspace subscription was left unchanged.`)
      await onChanged()
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="stack">
      {error && <Alert tone="error">{error}</Alert>}
      {notice && <Alert tone="success">{notice}</Alert>}

      <Card flush>
        <div className="toolbar toolbar--wrap">
          <div className="filter-pills">
            {STATUS_FILTERS.map((f) => (
              <button
                key={f.value}
                type="button"
                className={`filter-pill ${statusFilter === f.value ? 'filter-pill--active' : ''}`}
                onClick={() => setStatusFilter(f.value)}
              >
                <span>{f.label}</span>
                <span className="filter-pill__count">{counts[f.value] ?? 0}</span>
              </button>
            ))}
          </div>

          <span className="toolbar__spacer" />

          <div className="search-input-wrap">
            <IconSearch width={15} height={15} />
            <input
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search college, user, or UTR…"
            />
          </div>
        </div>

        {filtered.length === 0 ? (
          <EmptyState
            title={
              statusFilter === 'PENDING'
                ? 'No pending payment verifications'
                : 'No matching subscription requests'
            }
          >
            {statusFilter === 'PENDING'
              ? 'All submitted upgrade and capacity requests have been reviewed.'
              : 'Try switching the status filter or clearing the search box.'}
          </EmptyState>
        ) : (
          <Table
            columns={[
              {
                key: 'institution',
                title: 'Institution',
                render: (r) => (
                  <div>
                    <div className="strong">{r.institutionName}</div>
                    <div className="small faint">
                      by {r.requestedByUsername || 'admin'} · {moment(r.createdAt)}
                    </div>
                  </div>
                ),
              },
              {
                key: 'upgrade',
                title: 'Requested Upgrade',
                render: (r) => (
                  <div>
                    <div className="row" style={{ gap: 6 }}>
                      <Badge>{r.currentPlanCode}</Badge>
                      <span className="faint">→</span>
                      <Badge tone="brand">
                        {r.requestedPlanName || r.requestedPlanCode || r.currentPlanCode}
                      </Badge>
                    </div>
                    {describeCustomLimits(r) && (
                      <div className="small muted" style={{ marginTop: 4 }}>
                        {describeCustomLimits(r)}
                      </div>
                    )}
                  </div>
                ),
              },
              {
                key: 'payment',
                title: 'Payment & UTR',
                render: (r) => (
                  <div>
                    <div className="row" style={{ gap: 6 }}>
                      <code className="small strong">{r.transactionReference}</code>
                      <button
                        type="button"
                        className="icon-button"
                        style={{ width: 24, height: 24 }}
                        title="Copy UTR / Transaction ID"
                        onClick={() => copyTx(r.id, r.transactionReference)}
                      >
                        <IconCopy width={13} height={13} />
                      </button>
                      {copiedId === r.id && <span className="small" style={{ color: 'var(--success)' }}>Copied</span>}
                    </div>
                    <div className="small muted">
                      <strong>${Number(r.paymentAmount).toFixed(2)}</strong> · {r.paymentDate} ·{' '}
                      {r.paymentMethod?.replace('_', ' ')}
                    </div>
                  </div>
                ),
              },
              {
                key: 'status',
                title: 'Status',
                render: (r) => (
                  <div>
                    <StatusBadge status={r.status} />
                    {r.reviewedByUsername && (
                      <div className="small faint" style={{ marginTop: 3 }}>
                        by {r.reviewedByUsername}
                      </div>
                    )}
                  </div>
                ),
              },
              {
                key: 'actions',
                title: 'Actions',
                render: (r) => (
                  <div className="btn-row">
                    <button
                      type="button"
                      className="btn btn--sm"
                      onClick={() => {
                        setAdminNotes(r.adminNotes || '')
                        setInspecting(r)
                      }}
                    >
                      <IconEye width={14} height={14} />
                      Review
                    </button>
                    {r.status === 'PENDING' && (
                      <>
                        <button
                          type="button"
                          className="btn btn--sm btn--primary"
                          disabled={busy}
                          onClick={() => handleApprove(r)}
                        >
                          <IconCheck width={14} height={14} />
                          Approve
                        </button>
                        <button
                          type="button"
                          className="btn btn--sm btn--danger"
                          disabled={busy}
                          onClick={() => {
                            setRejectReason('')
                            setAdminNotes('')
                            setRejecting(r)
                          }}
                        >
                          <IconXCircle width={14} height={14} />
                          Reject
                        </button>
                      </>
                    )}
                  </div>
                ),
              },
            ]}
            rows={filtered}
          />
        )}
      </Card>

      {/* Detailed Verification Modal */}
      {inspecting && (
        <Modal
          wide
          title={`Verify Request #${inspecting.id} · ${inspecting.institutionName}`}
          onClose={() => !busy && setInspecting(null)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setInspecting(null)}>
                Close
              </button>
              {inspecting.status === 'PENDING' && (
                <>
                  <button
                    type="button"
                    className="btn btn--danger"
                    disabled={busy}
                    onClick={() => {
                      setRejectReason('')
                      setRejecting(inspecting)
                    }}
                  >
                    Reject Request…
                  </button>
                  <button
                    type="button"
                    className="btn btn--primary"
                    disabled={busy}
                    onClick={() => handleApprove(inspecting, adminNotes)}
                  >
                    {busy && <span className="spinner" />}
                    Verify Payment &amp; Approve
                  </button>
                </>
              )}
            </>
          }
        >
          <div className="stack">
            <div className="grid grid--2">
              <div className="detail-panel">
                <h4>Institution &amp; Requested Entitlements</h4>
                <dl className="kv-list">
                  <div>
                    <dt>Institution</dt>
                    <dd>{inspecting.institutionName}</dd>
                  </div>
                  <div>
                    <dt>Submitted By</dt>
                    <dd>{inspecting.requestedByUsername || '—'}</dd>
                  </div>
                  <div>
                    <dt>Plan Change</dt>
                    <dd>
                      {inspecting.currentPlanCode} →{' '}
                      <strong>
                        {inspecting.requestedPlanName ||
                          inspecting.requestedPlanCode ||
                          `${inspecting.currentPlanCode} (unchanged)`}
                      </strong>
                    </dd>
                  </div>
                  <div>
                    <dt>Custom Limits</dt>
                    <dd>{describeCustomLimits(inspecting) || 'Standard plan defaults'}</dd>
                  </div>
                  {instMap.get(inspecting.institutionId) && (
                    <div>
                      <dt>Current Usage</dt>
                      <dd>
                        {instMap.get(inspecting.institutionId).studentsUsed} /{' '}
                        {instMap.get(inspecting.institutionId).studentsLimit} students ·{' '}
                        {instMap.get(inspecting.institutionId).roomsUsed} /{' '}
                        {instMap.get(inspecting.institutionId).roomsLimit} halls
                      </dd>
                    </div>
                  )}
                  {inspecting.clientNotes && (
                    <div>
                      <dt>Client Notes</dt>
                      <dd>{inspecting.clientNotes}</dd>
                    </div>
                  )}
                </dl>
              </div>

              <div className="detail-panel">
                <h4>Submitted Payment Evidence</h4>
                <dl className="kv-list">
                  <div>
                    <dt>Status</dt>
                    <dd><StatusBadge status={inspecting.status} /></dd>
                  </div>
                  <div>
                    <dt>Transaction / UTR</dt>
                    <dd className="mono strong">{inspecting.transactionReference}</dd>
                  </div>
                  <div>
                    <dt>Amount Paid</dt>
                    <dd><strong>${Number(inspecting.paymentAmount).toFixed(2)}</strong></dd>
                  </div>
                  <div>
                    <dt>Payment Date</dt>
                    <dd>{inspecting.paymentDate}</dd>
                  </div>
                  <div>
                    <dt>Method</dt>
                    <dd>{inspecting.paymentMethod}</dd>
                  </div>
                  {inspecting.rejectionReason && (
                    <div>
                      <dt>Rejection Reason</dt>
                      <dd style={{ color: 'var(--danger)' }}>{inspecting.rejectionReason}</dd>
                    </div>
                  )}
                </dl>
              </div>
            </div>

            {inspecting.hasScreenshot ? (
              <div>
                <h4 style={{ marginBottom: 8 }}>Uploaded Payment Screenshot</h4>
                <div className="proof-frame">
                  <AuthImage
                    path={adminApi.requestScreenshotPath(inspecting.id)}
                    alt="Uploaded payment proof"
                    className="proof-modal-img"
                  />
                </div>
              </div>
            ) : (
              <div className="small muted" style={{ padding: '10px 14px', background: 'var(--surface-2)', borderRadius: 8 }}>
                No screenshot image was attached to this request. Verify using the UTR / Transaction ID above.
              </div>
            )}

            {inspecting.status === 'PENDING' && (
              <Field label="Internal Verification Notes (Optional)">
                <input
                  type="text"
                  value={adminNotes}
                  onChange={(e) => setAdminNotes(e.target.value)}
                  placeholder="e.g. Verified against bank credit statement"
                />
              </Field>
            )}
          </div>
        </Modal>
      )}

      {/* Reject Reason Modal */}
      {rejecting && (
        <Modal
          title={`Reject Request #${rejecting.id} · ${rejecting.institutionName}`}
          onClose={() => !busy && setRejecting(null)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setRejecting(null)}>
                Cancel
              </button>
              <button
                type="submit"
                form="reject-request-form"
                className="btn btn--danger"
                disabled={busy}
              >
                {busy && <span className="spinner" />}
                Confirm Rejection
              </button>
            </>
          }
        >
          <form id="reject-request-form" onSubmit={handleRejectSubmit} className="stack">
            <p className="small muted">
              Rejecting this request leaves <strong>{rejecting.institutionName}</strong>&apos;s
              current plan and limits untouched. You may optionally share a reason so the
              college administrator knows how to resolve the issue.
            </p>
            <Field label="Rejection Reason (Optional — visible to institution admin)">
              <textarea
                rows={3}
                value={rejectReason}
                onChange={(e) => setRejectReason(e.target.value)}
                placeholder="e.g. UTR number could not be matched or amount transferred did not match selected plan."
              />
            </Field>
          </form>
        </Modal>
      )}
    </div>
  )
}

function describeCustomLimits(r) {
  const parts = []
  if (r.requestedMaxStudents) parts.push(`${r.requestedMaxStudents.toLocaleString()} students`)
  if (r.requestedMaxRooms) parts.push(`${r.requestedMaxRooms} halls`)
  if (r.requestedMaxGenerations) parts.push(`${r.requestedMaxGenerations} gens/mo`)
  if (r.requestedSeatsPerGeneration) parts.push(`${r.requestedSeatsPerGeneration.toLocaleString()} seats/gen`)
  if (r.requestedStorageLimitMb) parts.push(`${r.requestedStorageLimitMb} MB`)
  return parts.join(' · ')
}
