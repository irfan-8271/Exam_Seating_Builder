import { useCallback, useEffect, useState } from 'react'
import { institutionApi, paymentApi, planApi, usageApi } from '../api/endpoints'
import { useAuth } from '../auth/AuthContext'
import {
  Alert,
  AuthImage,
  Badge,
  Card,
  EmptyState,
  Field,
  InfoTip,
  Loading,
  Modal,
  Stat,
  StatusBadge,
  Table,
  moment,
} from '../components/ui'
import {
  IconCheck,
  IconCopy,
  IconCreditCard,
  IconEye,
  IconQrCode,
  IconSparkle,
  IconUpload,
} from '../components/Icons'

const TODAY_ISO = () => new Date().toISOString().slice(0, 10)

export default function SettingsPage() {
  const { isAdmin, refresh } = useAuth()
  const [institution, setInstitution] = useState(null)
  const [plans, setPlans] = useState([])
  const [subscription, setSubscription] = useState(null)
  const [usage, setUsage] = useState(null)
  const [paymentConfig, setPaymentConfig] = useState(null)
  const [requests, setRequests] = useState([])

  const [name, setName] = useState('')
  const [contactEmail, setContactEmail] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const [submittedConfirmation, setSubmittedConfirmation] = useState(null)
  const [busy, setBusy] = useState(false)

  // Upgrade / Limit-increase modal state
  const [modalOpen, setModalOpen] = useState(false)
  const [requestMode, setRequestMode] = useState('PLAN') // 'PLAN' | 'CUSTOM'
  const [selectedPlanCode, setSelectedPlanCode] = useState('')
  const [reqStudents, setReqStudents] = useState('')
  const [reqRooms, setReqRooms] = useState('')
  const [reqGenerations, setReqGenerations] = useState('')
  const [reqSeatsPerGen, setReqSeatsPerGen] = useState('')
  const [reqStorageMb, setReqStorageMb] = useState('')
  const [paymentMethod, setPaymentMethod] = useState('MANUAL_UPI')
  const [txRef, setTxRef] = useState('')
  const [amount, setAmount] = useState('')
  const [paymentDate, setPaymentDate] = useState(TODAY_ISO())
  const [screenshotFile, setScreenshotFile] = useState(null)
  const [clientNotes, setClientNotes] = useState('')
  const [formError, setFormError] = useState(null)
  const [copiedUpi, setCopiedUpi] = useState(false)

  // Proof preview modal
  const [viewingProofRequest, setViewingProofRequest] = useState(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      const [inst, planList, sub, use, payCfg, reqList] = await Promise.all([
        institutionApi.current(),
        planApi.list(),
        planApi.subscription(),
        usageApi.usage().catch(() => null),
        paymentApi.config().catch(() => null),
        paymentApi.myRequests().catch(() => []),
      ])
      setInstitution(inst)
      setName(inst?.name || '')
      setContactEmail(inst?.contactEmail || '')
      setPlans(planList || [])
      setSubscription(sub)
      setUsage(use)
      setPaymentConfig(payCfg)
      setRequests(reqList || [])
    } catch (failure) {
      setError(failure.message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function saveInstitution(event) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const updated = await institutionApi.update({ name, contactEmail })
      setInstitution(updated)
      await refresh()
      setNotice('Institution profile saved.')
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  function openUpgradeModal(plan = null, customOnly = false) {
    setFormError(null)
    setCopiedUpi(false)
    if (customOnly) {
      setRequestMode('CUSTOM')
      setSelectedPlanCode('')
      setAmount('')
    } else {
      setRequestMode('PLAN')
      const code = plan?.code || ''
      setSelectedPlanCode(code)
      setAmount(plan && Number(plan.monthlyPrice) > 0 ? String(plan.monthlyPrice) : '')
    }
    setReqStudents('')
    setReqRooms('')
    setReqGenerations('')
    setReqSeatsPerGen('')
    setReqStorageMb('')
    setPaymentMethod('MANUAL_UPI')
    setTxRef('')
    setPaymentDate(TODAY_ISO())
    setScreenshotFile(null)
    setClientNotes('')
    setModalOpen(true)
  }

  function handlePlanSelectChange(newCode) {
    setSelectedPlanCode(newCode)
    const matched = plans.find((p) => p.code === newCode)
    if (matched && Number(matched.monthlyPrice) > 0) {
      setAmount(String(matched.monthlyPrice))
    }
  }

  async function copyUpiId() {
    if (!paymentConfig?.upiId) return
    try {
      await navigator.clipboard.writeText(paymentConfig.upiId)
      setCopiedUpi(true)
      setTimeout(() => setCopiedUpi(false), 2000)
    } catch {
      setCopiedUpi(false)
    }
  }

  async function submitUpgradeRequest(event) {
    event.preventDefault()
    setFormError(null)

    const hasCustomLimits =
      reqStudents !== '' ||
      reqRooms !== '' ||
      reqGenerations !== '' ||
      reqSeatsPerGen !== '' ||
      reqStorageMb !== ''

    if (!selectedPlanCode && !hasCustomLimits) {
      setFormError('Select a target plan or specify at least one custom limit increase.')
      return
    }
    if (!txRef.trim()) {
      setFormError('Enter your payment Transaction ID / UTR number.')
      return
    }
    if (!amount || Number(amount) <= 0) {
      setFormError('Enter a valid payment amount greater than 0.')
      return
    }
    if (!paymentDate) {
      setFormError('Select your payment date.')
      return
    }
    if (paymentConfig?.requireScreenshot && !screenshotFile) {
      setFormError('Please attach a payment screenshot as required for verification.')
      return
    }

    setBusy(true)
    try {
      const created = await paymentApi.submitRequest(
        {
          requestedPlanCode: selectedPlanCode || undefined,
          requestedMaxStudents: reqStudents || undefined,
          requestedMaxRooms: reqRooms || undefined,
          requestedMaxGenerations: reqGenerations || undefined,
          requestedSeatsPerGeneration: reqSeatsPerGen || undefined,
          requestedStorageLimitMb: reqStorageMb || undefined,
          clientNotes: clientNotes.trim() || undefined,
          paymentMethod,
          transactionReference: txRef.trim(),
          paymentAmount: amount,
          paymentDate,
        },
        screenshotFile,
      )
      setModalOpen(false)
      setSubmittedConfirmation(
        created?.confirmationMessage ||
          'Your upgrade request has been submitted successfully. Your subscription limits will be updated within 2–3 working days, subject to payment verification and administrative approval.',
      )
      await load()
    } catch (failure) {
      setFormError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <Loading label="Loading subscription & workspace settings…" />

  const pendingCount = requests.filter((r) => r.status === 'PENDING').length
  const storageUsedMb = Math.round((usage?.storageUsedBytes || 0) / (1024 * 1024))
  const storageLimitMb = Math.round((usage?.storageLimitBytes || 0) / (1024 * 1024))

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Institution, Plan &amp; Billing</h1>
          <p>
            Manage your institution profile, monitor real-time subscription quotas, and
            submit verified plan or capacity upgrade requests.
          </p>
        </div>
        {isAdmin && (
          <div className="btn-row">
            <button
              type="button"
              className="btn"
              onClick={() => openUpgradeModal(null, true)}
            >
              Request Custom Limits
            </button>
            <button
              type="button"
              className="btn btn--primary"
              onClick={() => {
                const nextPlan =
                  plans.find(
                    (p) =>
                      p.code !== subscription?.planCode && Number(p.monthlyPrice) > 0,
                  ) || null
                openUpgradeModal(nextPlan, false)
              }}
            >
              <IconSparkle width={16} height={16} />
              Request Plan Upgrade
            </button>
          </div>
        )}
      </div>

      <div className="stack">
        {error && <Alert tone="error">{error}</Alert>}
        {notice && <Alert tone="success">{notice}</Alert>}

        {submittedConfirmation && (
          <Alert
            tone="success"
            title="Upgrade Request Submitted!"
          >
            {submittedConfirmation}
          </Alert>
        )}

        {pendingCount > 0 && !submittedConfirmation && (
          <Alert tone="warning" title={`${pendingCount} Pending Verification Request${pendingCount > 1 ? 's' : ''}:`}>
            Your submitted payment details are queued for platform administrator review.
            Current plan limits remain in effect until verification is approved (typically within 2–3 working days).
          </Alert>
        )}

        {usage && (
          <Card
            title="Current Subscription & Effective Quotas"
            subtitle={
              subscription
                ? `Active Plan: ${subscription.planName || subscription.planCode}${
                    usage.periodEnd ? ` · Billing window ends ${moment(usage.periodEnd)}` : ''
                  }`
                : 'Current workspace limits and consumption'
            }
            accent="brand"
            actions={
              <div className="row" style={{ gap: 8 }}>
                <Badge tone="brand">{subscription?.planName || subscription?.planCode || 'FREE'}</Badge>
                <StatusBadge status={subscription?.status || 'ACTIVE'} />
              </div>
            }
          >
            <div className="grid grid--4">
              <Stat
                label="Students"
                value={usage.studentsUsed}
                limit={usage.studentsLimit}
                accent="brand"
                hint={`${usage.seatsPerGeneration?.toLocaleString() || '—'} max seats / generation`}
              />
              <Stat
                label="Exam Halls"
                value={usage.roomsUsed}
                limit={usage.roomsLimit}
                accent="emerald"
                hint="Configured examination rooms"
              />
              <Stat
                label="Monthly Generations"
                value={usage.generationsUsed}
                limit={usage.generationsLimit}
                accent="amber"
                hint="Resets each billing cycle"
              />
              <Stat
                label="File Storage (MB)"
                value={storageUsedMb}
                limit={storageLimitMb}
                unit=" MB"
                accent="violet"
                hint="Uploaded rosters & imports"
              />
            </div>
          </Card>
        )}

        <Card
          title="Subscription Plans"
          subtitle="Upgrades are activated after manual payment verification and platform administrator approval."
          actions={
            <InfoTip title="How Subscription Upgrades Work">
              Select a target plan or custom limit increase, complete the fee payment using
              the administrator&apos;s UPI ID / QR code, and submit your UTR / Transaction ID.
              Once verified by the platform administrator, your new limits take effect immediately.
            </InfoTip>
          }
        >
          <div className="plan-grid">
            {plans.map((plan) => {
              const isCurrent = plan.code === subscription?.planCode
              const price = Number(plan.monthlyPrice || 0)
              const popular = plan.code === 'PRO'
              return (
                <div
                  key={plan.code}
                  className={
                    'plan-card' +
                    (isCurrent ? ' plan-card--current' : '') +
                    (popular ? ' plan-card--popular' : '')
                  }
                >
                  <div className="plan-card__head">
                    <div className="row row--between">
                      <span className="plan-card__name">{plan.name}</span>
                      {isCurrent ? (
                        <Badge tone="success">Current Plan</Badge>
                      ) : popular ? (
                        <Badge tone="brand">Recommended</Badge>
                      ) : null}
                    </div>
                    <div className="plan-card__price">
                      {price === 0 ? (
                        <span>Free</span>
                      ) : (
                        <>
                          <span>${price}</span>
                          <small>/ month</small>
                        </>
                      )}
                    </div>
                  </div>

                  <ul className="plan-card__features">
                    <li>
                      <IconCheck width={15} height={15} />
                      <span>Up to <strong>{plan.maxStudents.toLocaleString()}</strong> students</span>
                    </li>
                    <li>
                      <IconCheck width={15} height={15} />
                      <span>Up to <strong>{plan.maxRooms.toLocaleString()}</strong> exam halls</span>
                    </li>
                    <li>
                      <IconCheck width={15} height={15} />
                      <span><strong>{plan.maxGenerationsPerMonth.toLocaleString()}</strong> generations / month</span>
                    </li>
                    <li>
                      <IconCheck width={15} height={15} />
                      <span><strong>{plan.seatsPerGeneration.toLocaleString()}</strong> seats / generation</span>
                    </li>
                    <li>
                      <IconCheck width={15} height={15} />
                      <span><strong>{plan.storageLimitMb.toLocaleString()} MB</strong> file storage</span>
                    </li>
                  </ul>

                  <div className="plan-card__foot">
                    {isCurrent ? (
                      isAdmin ? (
                        <button
                          type="button"
                          className="btn btn--sm btn--block"
                          onClick={() => openUpgradeModal(null, true)}
                        >
                          Request Extra Limits
                        </button>
                      ) : (
                        <span className="small muted center" style={{ display: 'block' }}>
                          Active workspace plan
                        </span>
                      )
                    ) : price === 0 ? (
                      <span className="small faint center" style={{ display: 'block' }}>
                        Included starter tier
                      </span>
                    ) : isAdmin ? (
                      <button
                        type="button"
                        className={`btn btn--sm btn--block ${popular ? 'btn--primary' : ''}`}
                        onClick={() => openUpgradeModal(plan, false)}
                      >
                        Request {plan.name}
                      </button>
                    ) : (
                      <span className="small muted center" style={{ display: 'block' }}>
                        Ask an institution Admin to upgrade
                      </span>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        </Card>

        <div className="grid grid--2">
          <Card
            title="Upgrade & Payment Request History"
            subtitle="Track the verification status of your submitted requests."
            flush
          >
            {requests.length === 0 ? (
              <EmptyState title="No upgrade requests submitted yet">
                When you submit a plan upgrade or custom capacity request, its payment
                verification status will appear here.
              </EmptyState>
            ) : (
              <Table
                columns={[
                  {
                    key: 'target',
                    title: 'Requested Upgrade',
                    render: (r) => (
                      <div>
                        <div className="strong">
                          {r.requestedPlanName || r.requestedPlanCode || 'Custom Limit Increase'}
                        </div>
                        <div className="small faint">{formatRequestedLimits(r)}</div>
                      </div>
                    ),
                  },
                  {
                    key: 'payment',
                    title: 'Payment Details',
                    render: (r) => (
                      <div>
                        <div className="mono small strong">{r.transactionReference}</div>
                        <div className="small muted">
                          ${Number(r.paymentAmount).toFixed(2)} · {r.paymentDate}
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
                        {r.rejectionReason && (
                          <div className="small" style={{ color: 'var(--danger)', marginTop: 4 }}>
                            Reason: {r.rejectionReason}
                          </div>
                        )}
                        {r.reviewedAt && (
                          <div className="small faint" style={{ marginTop: 2 }}>
                            {moment(r.reviewedAt)}
                          </div>
                        )}
                      </div>
                    ),
                  },
                  {
                    key: 'proof',
                    title: 'Proof',
                    render: (r) =>
                      r.hasScreenshot ? (
                        <button
                          type="button"
                          className="btn btn--sm"
                          onClick={() => setViewingProofRequest(r)}
                        >
                          <IconEye width={14} height={14} />
                          View
                        </button>
                      ) : (
                        <span className="small faint">—</span>
                      ),
                  },
                ]}
                rows={requests}
              />
            )}
          </Card>

          <Card
            title="Institution Profile"
            subtitle={
              institution
                ? `Workspace slug: ${institution.slug} · Status: ${institution.status}`
                : 'Workspace details'
            }
          >
            <form onSubmit={saveInstitution}>
              <Field label="Institution / College Name">
                <input
                  type="text"
                  value={name}
                  disabled={!isAdmin}
                  onChange={(event) => setName(event.target.value)}
                  required
                />
              </Field>
              <Field label="Administrative Contact Email" hint="Used for billing & operational notices.">
                <input
                  type="email"
                  value={contactEmail}
                  disabled={!isAdmin}
                  onChange={(event) => setContactEmail(event.target.value)}
                  placeholder="exams@college.edu"
                />
              </Field>
              {isAdmin ? (
                <button type="submit" className="btn btn--primary" disabled={busy}>
                  Save Institution Profile
                </button>
              ) : (
                <p className="small muted">Only an institution ADMIN can edit profile details.</p>
              )}
            </form>
          </Card>
        </div>
      </div>

      {/* Upgrade & Manual Payment Verification Modal */}
      {modalOpen && (
        <Modal
          wide
          title={
            requestMode === 'CUSTOM'
              ? 'Request Custom Limit Increase'
              : 'Request Subscription Upgrade'
          }
          onClose={() => !busy && setModalOpen(false)}
          footer={
            <>
              <button
                type="button"
                className="btn"
                disabled={busy}
                onClick={() => setModalOpen(false)}
              >
                Cancel
              </button>
              <button
                type="submit"
                form="upgrade-request-form"
                className="btn btn--primary"
                disabled={busy}
              >
                {busy && <span className="spinner" />}
                {busy ? 'Submitting Request…' : 'Submit Upgrade Request'}
              </button>
            </>
          }
        >
          <form id="upgrade-request-form" onSubmit={submitUpgradeRequest} className="stack">
            {formError && <Alert tone="error">{formError}</Alert>}

            {/* Step 1: Payment Instructions & QR */}
            <div className="payment-box">
              <div className="payment-box__info">
                <div className="payment-box__kicker">
                  <IconCreditCard width={16} height={16} />
                  <span>Step 1 · Complete Payment Externally</span>
                </div>
                <p className="small" style={{ marginBottom: 10 }}>
                  {paymentConfig?.instructions ||
                    'Transfer the plan or capacity fee using the UPI ID or QR code below, then record your UTR / Transaction ID in Step 2.'}
                </p>

                {paymentConfig?.payeeName && (
                  <div className="small muted" style={{ marginBottom: 6 }}>
                    Payee: <strong>{paymentConfig.payeeName}</strong>
                  </div>
                )}

                {paymentConfig?.upiId && (
                  <div className="upi-pill">
                    <div>
                      <span className="upi-pill__label">UPI ID</span>
                      <code className="upi-pill__value">{paymentConfig.upiId}</code>
                    </div>
                    <button
                      type="button"
                      className="btn btn--sm"
                      onClick={copyUpiId}
                    >
                      <IconCopy width={14} height={14} />
                      {copiedUpi ? 'Copied!' : 'Copy'}
                    </button>
                  </div>
                )}

                {paymentConfig?.additionalInfo && (
                  <p className="small faint" style={{ marginTop: 8, marginBottom: 0 }}>
                    {paymentConfig.additionalInfo}
                  </p>
                )}
              </div>

              {paymentConfig?.hasQrImage && (
                <div className="payment-box__qr">
                  <div className="small strong" style={{ marginBottom: 6 }}>
                    <IconQrCode width={14} height={14} style={{ verticalAlign: -2 }} /> Scan to Pay
                  </div>
                  <AuthImage
                    path={paymentApi.qrPath()}
                    alt="Payment UPI QR Code"
                    className="qr-preview-img"
                  />
                </div>
              )}
            </div>

            {/* Step 2: Plan & Custom Limits */}
            <div>
              <h3 style={{ marginBottom: 10 }}>Step 2 · Select Plan &amp; Optional Capacity Add-ons</h3>
              <div className="grid grid--2">
                <Field
                  label="Target Subscription Plan"
                  hint="Leave on current plan if requesting custom limits only."
                >
                  <select
                    value={selectedPlanCode}
                    onChange={(e) => handlePlanSelectChange(e.target.value)}
                  >
                    <option value="">
                      Keep current plan ({subscription?.planName || subscription?.planCode || 'FREE'}) — custom limits only
                    </option>
                    {plans
                      .filter((p) => p.code !== 'FREE' && p.code !== subscription?.planCode)
                      .map((p) => (
                        <option key={p.code} value={p.code}>
                          {p.name} — ${Number(p.monthlyPrice)}/mo ({p.maxStudents.toLocaleString()} students, {p.maxRooms} halls)
                        </option>
                      ))}
                  </select>
                </Field>

                <Field label="Payment Method">
                  <select
                    value={paymentMethod}
                    onChange={(e) => setPaymentMethod(e.target.value)}
                  >
                    <option value="MANUAL_UPI">UPI / QR Transfer</option>
                    <option value="BANK_TRANSFER">NEFT / IMPS / RTGS Bank Transfer</option>
                    <option value="OTHER_MANUAL">Institutional Cheque / Other</option>
                  </select>
                </Field>
              </div>

              <details className="custom-limits-expander" open={requestMode === 'CUSTOM'}>
                <summary>
                  Custom Capacity Limits {requestMode === 'CUSTOM' ? '(Specify needed limits)' : '(Optional)'}
                </summary>
                <div className="grid grid--2" style={{ marginTop: 12 }}>
                  <Field label="Requested Max Students" hint={`Current limit: ${usage?.studentsLimit ?? '—'}`}>
                    <input
                      type="number"
                      min="1"
                      placeholder="e.g. 2500"
                      value={reqStudents}
                      onChange={(e) => setReqStudents(e.target.value)}
                    />
                  </Field>
                  <Field label="Requested Max Exam Halls" hint={`Current limit: ${usage?.roomsLimit ?? '—'}`}>
                    <input
                      type="number"
                      min="1"
                      placeholder="e.g. 60"
                      value={reqRooms}
                      onChange={(e) => setReqRooms(e.target.value)}
                    />
                  </Field>
                  <Field label="Requested Generations / Month" hint={`Current limit: ${usage?.generationsLimit ?? '—'}`}>
                    <input
                      type="number"
                      min="1"
                      placeholder="e.g. 200"
                      value={reqGenerations}
                      onChange={(e) => setReqGenerations(e.target.value)}
                    />
                  </Field>
                  <Field label="Requested Max Seats / Generation" hint={`Current limit: ${usage?.seatsPerGeneration ?? '—'}`}>
                    <input
                      type="number"
                      min="1"
                      placeholder="e.g. 3000"
                      value={reqSeatsPerGen}
                      onChange={(e) => setReqSeatsPerGen(e.target.value)}
                    />
                  </Field>
                </div>
              </details>
            </div>

            {/* Step 3: Payment Proof */}
            <div>
              <h3 style={{ marginBottom: 10 }}>Step 3 · Payment Verification Details</h3>
              <div className="grid grid--3">
                <Field label="Transaction ID / UTR Number *" hint="12-digit UPI UTR or bank ref">
                  <input
                    type="text"
                    value={txRef}
                    onChange={(e) => setTxRef(e.target.value)}
                    placeholder="e.g. 431589204118"
                    required
                  />
                </Field>
                <Field label="Amount Paid (USD) *" hint="Exact amount transferred">
                  <input
                    type="number"
                    min="0.01"
                    step="0.01"
                    value={amount}
                    onChange={(e) => setAmount(e.target.value)}
                    placeholder="e.g. 19.00"
                    required
                  />
                </Field>
                <Field label="Payment Date *">
                  <input
                    type="date"
                    max={TODAY_ISO()}
                    value={paymentDate}
                    onChange={(e) => setPaymentDate(e.target.value)}
                    required
                  />
                </Field>
              </div>

              <div className="grid grid--2">
                <Field
                  label={
                    paymentConfig?.requireScreenshot
                      ? 'Payment Screenshot * (Required)'
                      : 'Payment Screenshot (Recommended)'
                  }
                  hint="PNG, JPG, WEBP, or GIF up to 5 MB."
                >
                  <label className="upload-drop">
                    <IconUpload width={18} height={18} />
                    <span>
                      {screenshotFile
                        ? `${screenshotFile.name} (${Math.round(screenshotFile.size / 1024)} KB)`
                        : 'Choose payment receipt screenshot…'}
                    </span>
                    <input
                      type="file"
                      accept="image/png,image/jpeg,image/webp,image/gif"
                      onChange={(e) => setScreenshotFile(e.target.files?.[0] || null)}
                      style={{ display: 'none' }}
                    />
                  </label>
                </Field>

                <Field label="Notes for Administrator (Optional)" hint="Any PO number or exam schedule note.">
                  <input
                    type="text"
                    value={clientNotes}
                    onChange={(e) => setClientNotes(e.target.value)}
                    placeholder="e.g. Urgent semester exams next week"
                  />
                </Field>
              </div>
            </div>

            <div className="small muted" style={{ background: 'var(--surface-2)', padding: '10px 14px', borderRadius: 8 }}>
              <strong>Important:</strong> Submitting payment details places your request in{' '}
              <strong>Pending Verification</strong> status. Subscription limits update only
              after a platform administrator verifies the transaction and approves your request
              (within 2–3 working days).
            </div>
          </form>
        </Modal>
      )}

      {viewingProofRequest && (
        <Modal
          title={`Payment Proof · ${viewingProofRequest.transactionReference}`}
          onClose={() => setViewingProofRequest(null)}
          footer={
            <button type="button" className="btn" onClick={() => setViewingProofRequest(null)}>
              Close
            </button>
          }
        >
          <div className="center">
            <AuthImage
              path={paymentApi.myScreenshotPath(viewingProofRequest.id)}
              alt="Payment Screenshot"
              className="proof-modal-img"
            />
          </div>
        </Modal>
      )}
    </>
  )
}

function formatRequestedLimits(r) {
  const parts = []
  if (r.requestedMaxStudents) parts.push(`${r.requestedMaxStudents.toLocaleString()} students`)
  if (r.requestedMaxRooms) parts.push(`${r.requestedMaxRooms} halls`)
  if (r.requestedMaxGenerations) parts.push(`${r.requestedMaxGenerations} gens/mo`)
  if (r.requestedSeatsPerGeneration) parts.push(`${r.requestedSeatsPerGeneration.toLocaleString()} seats/gen`)
  if (r.requestedStorageLimitMb) parts.push(`${r.requestedStorageLimitMb} MB`)
  if (!parts.length) return `From ${r.currentPlanCode} tier`
  return parts.join(' · ')
}
