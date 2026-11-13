import { useEffect, useState } from 'react'
import { adminApi } from '../../api/endpoints'
import { Alert, AuthImage, Badge, Card, Field, moment } from '../../components/ui'
import { IconQrCode, IconTrash, IconUpload } from '../../components/Icons'

export default function PaymentConfigTab({ config, onChanged }) {
  const [upiId, setUpiId] = useState('')
  const [payeeName, setPayeeName] = useState('')
  const [instructions, setInstructions] = useState('')
  const [additionalInfo, setAdditionalInfo] = useState('')
  const [requireScreenshot, setRequireScreenshot] = useState(false)
  const [qrRefreshKey, setQrRefreshKey] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)

  useEffect(() => {
    if (!config) return
    setUpiId(config.upiId || '')
    setPayeeName(config.payeeName || '')
    setInstructions(config.instructions || '')
    setAdditionalInfo(config.additionalInfo || '')
    setRequireScreenshot(Boolean(config.requireScreenshot))
  }, [config])

  async function saveDetails(event) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      await adminApi.updatePaymentConfig({
        paymentMode: 'MANUAL',
        upiId,
        payeeName,
        instructions,
        additionalInfo,
        requireScreenshot,
      })
      setNotice('Payment verification settings saved.')
      await onChanged()
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  async function handleQrUpload(event) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      await adminApi.uploadPaymentQr(file)
      setQrRefreshKey((k) => k + 1)
      setNotice(`Uploaded payment QR image (${file.name}).`)
      await onChanged()
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(false)
    }
  }

  async function handleQrRemove() {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      await adminApi.deletePaymentQr()
      setQrRefreshKey((k) => k + 1)
      setNotice('Removed payment QR image.')
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

      <div className="grid grid--2">
        <Card
          title="Manual Payment Instructions & UPI Details"
          subtitle="Displayed to institution administrators when they submit an upgrade or limit increase request."
          actions={<Badge tone="brand">{config?.paymentMode || 'MANUAL'}</Badge>}
        >
          <form onSubmit={saveDetails}>
            <div className="grid grid--2">
              <Field label="UPI ID / VPA" hint="Displayed with a one-click Copy button.">
                <input
                  type="text"
                  value={upiId}
                  onChange={(e) => setUpiId(e.target.value)}
                  placeholder="billing@okaxis"
                />
              </Field>
              <Field label="Payee / Beneficiary Name" hint="Account holder shown to payers.">
                <input
                  type="text"
                  value={payeeName}
                  onChange={(e) => setPayeeName(e.target.value)}
                  placeholder="ExamSeating Cloud Billing"
                />
              </Field>
            </div>

            <Field
              label="Step-by-Step Payment Instructions"
              hint="Clear directions shown inside the client upgrade request form."
            >
              <textarea
                rows={4}
                value={instructions}
                onChange={(e) => setInstructions(e.target.value)}
                placeholder="Pay the exact fee via UPI or bank transfer and submit your 12-digit UTR number..."
              />
            </Field>

            <Field
              label="Additional Bank / Billing Information (Optional)"
              hint="NEFT/IMPS account details, GST/invoice notes, or support contact."
            >
              <textarea
                rows={3}
                value={additionalInfo}
                onChange={(e) => setAdditionalInfo(e.target.value)}
                placeholder="Optional bank account / IFSC or billing support email..."
              />
            </Field>

            <label className="check" style={{ marginBottom: 16 }}>
              <input
                type="checkbox"
                checked={requireScreenshot}
                onChange={(e) => setRequireScreenshot(e.target.checked)}
              />
              <span>
                <strong>Require payment screenshot</strong> on every upgrade request submission
              </span>
            </label>

            <div className="row row--between">
              <button type="submit" className="btn btn--primary" disabled={busy}>
                {busy && <span className="spinner" />}
                Save Payment Configuration
              </button>
              {config?.updatedAt && (
                <span className="small faint">Updated {moment(config.updatedAt)}</span>
              )}
            </div>
          </form>
        </Card>

        <Card
          title="Payment QR Code Image"
          subtitle="Upload your merchant UPI QR code so institutions can scan & pay directly."
        >
          <div className="stack">
            {config?.hasQrImage ? (
              <div className="qr-admin-box">
                <AuthImage
                  path={adminApi.qrPath()}
                  refreshKey={qrRefreshKey}
                  alt="Configured Payment QR Code"
                  className="qr-preview-img"
                />
                <div className="small muted" style={{ marginTop: 8 }}>
                  {config.qrFilename || 'payment-qr.png'}
                </div>
              </div>
            ) : (
              <div className="qr-admin-box qr-admin-box--empty">
                <IconQrCode width={36} height={36} />
                <div className="strong" style={{ marginTop: 8 }}>No QR image uploaded yet</div>
                <div className="small muted">
                  Clients currently see your UPI ID and text instructions only.
                </div>
              </div>
            )}

            <div className="btn-row">
              <label className="btn btn--primary" style={{ cursor: busy ? 'not-allowed' : 'pointer' }}>
                <IconUpload width={16} height={16} />
                <span>{config?.hasQrImage ? 'Replace QR Image' : 'Upload QR Image'}</span>
                <input
                  type="file"
                  accept="image/png,image/jpeg,image/webp,image/gif"
                  disabled={busy}
                  onChange={handleQrUpload}
                  style={{ display: 'none' }}
                />
              </label>

              {config?.hasQrImage && (
                <button
                  type="button"
                  className="btn btn--danger"
                  disabled={busy}
                  onClick={handleQrRemove}
                >
                  <IconTrash width={15} height={15} />
                  Remove QR
                </button>
              )}
            </div>

            <div className="small faint">
              Supported formats: PNG, JPG, WEBP, GIF (max 5 MB). Designed with modular
              payment-mode architecture so automated gateways (Razorpay / Stripe) can be
              enabled in future releases without changing tenant data.
            </div>
          </div>
        </Card>
      </div>
    </div>
  )
}
