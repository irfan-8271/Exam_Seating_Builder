import { useState } from 'react'
import { adminApi } from '../../api/endpoints'
import { Alert, Badge, Card, Field, Modal, formatMoney } from '../../components/ui'
import { IconCheck, IconSettings, IconSparkle } from '../../components/Icons'

const EMPTY_PLAN = {
  code: '',
  name: '',
  monthlyPrice: '0',
  maxStudents: '500',
  maxRooms: '20',
  maxGenerationsPerMonth: '50',
  seatsPerGeneration: '500',
  storageLimitMb: '100',
  maxFileSizeMb: '5',
  sortOrder: '50',
  active: true,
}

export default function PlansTab({
  plans = [],
  paymentConfig,
  onChanged,
  onNavigatePayments,
}) {
  const [editingPlan, setEditingPlan] = useState(null) // null | { mode: 'create' | 'edit', plan }
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)

  const currencySymbol =
    paymentConfig?.currencySymbol || plans[0]?.currencySymbol || '₹'
  const currencyCode =
    paymentConfig?.currencyCode || plans[0]?.currencyCode || 'INR'

  const activeCount = plans.filter((p) => p.active).length
  const totalSubscribers = plans.reduce(
    (sum, p) => sum + Number(p.subscriberCount || 0),
    0,
  )

  async function togglePlanActive(plan) {
    if (plan.code === 'FREE') return
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      await adminApi.updatePlan(plan.code, {
        code: plan.code,
        name: plan.name,
        monthlyPrice: Number(plan.monthlyPrice || 0),
        maxStudents: Number(plan.maxStudents),
        maxRooms: Number(plan.maxRooms),
        maxGenerationsPerMonth: Number(plan.maxGenerationsPerMonth),
        storageLimitMb: Number(plan.storageLimitMb),
        maxFileSizeMb: Number(plan.maxFileSizeMb),
        seatsPerGeneration: Number(plan.seatsPerGeneration),
        sortOrder: Number(plan.sortOrder ?? 10),
        active: !plan.active,
      })
      setNotice(
        !plan.active
          ? `Published ${plan.name} (${plan.code}) to the public plan catalog.`
          : `Hidden ${plan.name} (${plan.code}) from new signups and upgrades.`,
      )
      await onChanged()
    } catch (failure) {
      setError(failure.message || 'Could not update plan visibility.')
    } finally {
      setBusy(false)
    }
  }

  async function handleSavePlan(mode, payload) {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      if (mode === 'create') {
        const created = await adminApi.createPlan(payload)
        setNotice(
          `Created subscription tier ${created.name} (${created.code}).`,
        )
      } else {
        const updated = await adminApi.updatePlan(payload.code, payload)
        setNotice(
          `Updated ${updated.name} (${updated.code}) limits & pricing across the platform.`,
        )
      }
      setEditingPlan(null)
      await onChanged()
    } catch (failure) {
      setError(failure.message || 'Could not save subscription tier.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="stack">
      {error && <Alert tone="error">{error}</Alert>}
      {notice && <Alert tone="success">{notice}</Alert>}

      <Card
        title="Global Subscription Tiers & Quota Catalog"
        subtitle={`${plans.length} configured tiers (${activeCount} public) · ${totalSubscribers} subscribed institution${
          totalSubscribers === 1 ? '' : 's'
        }`}
        actions={
          <div className="btn-row">
            {onNavigatePayments && (
              <button
                type="button"
                className="btn btn--sm"
                onClick={onNavigatePayments}
                title="Change billing currency symbol or code"
              >
                <IconSettings width={14} height={14} />
                Currency: {currencyCode} ({currencySymbol})
              </button>
            )}
            <button
              type="button"
              className="btn btn--sm btn--primary"
              onClick={() =>
                setEditingPlan({
                  mode: 'create',
                  plan: {
                    ...EMPTY_PLAN,
                    sortOrder: String((plans.length + 1) * 10),
                  },
                })
              }
            >
              <IconSparkle width={15} height={15} />
              Create Plan Tier
            </button>
          </div>
        }
      >
        <div className="rule-callout" style={{ marginBottom: 18 }}>
          <div className="small">
            <strong>Live Quota Propagation &amp; Safe Soft-Caps:</strong>{' '}
            Modifying any tier (including <code>FREE</code> Starter and paid tiers)
            immediately updates quotas across all institutions on that plan unless
            overridden for a specific college in <strong>Institutions</strong>.
            Lowering a limit never deletes existing college rosters or halls—it
            simply caps new additions until usage is within quota.
          </div>
        </div>

        <div className="plan-grid">
          {plans.map((plan) => {
            const price = Number(plan.monthlyPrice || 0)
            const isPopular = plan.code === 'PRO'
            const isFree = plan.code === 'FREE'
            return (
              <div
                key={plan.code}
                className={
                  'plan-card' +
                  (isPopular && plan.active ? ' plan-card--popular' : '') +
                  (!plan.active ? ' plan-card--inactive' : '')
                }
              >
                <div>
                  <div className="plan-card__head">
                    <div
                      className="row row--between"
                      style={{ gap: 6, flexWrap: 'wrap', marginBottom: 6 }}
                    >
                      <span className="plan-card__name">{plan.name}</span>
                      <div className="row" style={{ gap: 6 }}>
                        <Badge tone={plan.active ? 'ok' : 'warn'}>
                          {plan.active ? 'PUBLIC' : 'HIDDEN'}
                        </Badge>
                      </div>
                    </div>

                    <div
                      className="row row--between"
                      style={{ gap: 6, marginBottom: 4 }}
                    >
                      <code className="small muted">{plan.code}</code>
                      <span className="small muted">
                        <strong>{plan.subscriberCount ?? 0}</strong>{' '}
                        {Number(plan.subscriberCount) === 1
                          ? 'institution'
                          : 'institutions'}
                      </span>
                    </div>

                    <div className="plan-card__price">
                      {price === 0 ? (
                        <span>Free</span>
                      ) : (
                        <>
                          <span>
                            {formatMoney(price, {
                              symbol: currencySymbol,
                              code: currencyCode,
                            })}
                          </span>
                          <small>/ month</small>
                        </>
                      )}
                    </div>
                  </div>

                  <ul className="plan-card__features">
                    <li>
                      <IconCheck width={15} height={15} />
                      <span>
                        <strong>{Number(plan.maxStudents).toLocaleString()}</strong>{' '}
                        max students
                      </span>
                    </li>
                    <li>
                      <IconCheck width={15} height={15} />
                      <span>
                        <strong>{Number(plan.maxRooms).toLocaleString()}</strong>{' '}
                        exam halls
                      </span>
                    </li>
                    <li>
                      <IconCheck width={15} height={15} />
                      <span>
                        <strong>
                          {Number(plan.maxGenerationsPerMonth).toLocaleString()}
                        </strong>{' '}
                        generations / month
                      </span>
                    </li>
                    <li>
                      <IconCheck width={15} height={15} />
                      <span>
                        <strong>
                          {Number(plan.seatsPerGeneration).toLocaleString()}
                        </strong>{' '}
                        seats / generation
                      </span>
                    </li>
                    <li>
                      <IconCheck width={15} height={15} />
                      <span>
                        <strong>
                          {Number(plan.storageLimitMb).toLocaleString()} MB
                        </strong>{' '}
                        total storage
                      </span>
                    </li>
                    <li>
                      <IconCheck width={15} height={15} />
                      <span>
                        <strong>
                          {Number(plan.maxFileSizeMb).toLocaleString()} MB
                        </strong>{' '}
                        max upload file
                      </span>
                    </li>
                  </ul>
                </div>

                <div className="plan-card__foot">
                  <div className="btn-row" style={{ width: '100%' }}>
                    <button
                      type="button"
                      className="btn btn--sm btn--primary"
                      style={{ flex: 1 }}
                      disabled={busy}
                      onClick={() =>
                        setEditingPlan({
                          mode: 'edit',
                          plan: {
                            code: plan.code,
                            name: plan.name,
                            monthlyPrice: String(plan.monthlyPrice ?? 0),
                            maxStudents: String(plan.maxStudents),
                            maxRooms: String(plan.maxRooms),
                            maxGenerationsPerMonth: String(
                              plan.maxGenerationsPerMonth,
                            ),
                            seatsPerGeneration: String(plan.seatsPerGeneration),
                            storageLimitMb: String(plan.storageLimitMb),
                            maxFileSizeMb: String(plan.maxFileSizeMb),
                            sortOrder: String(plan.sortOrder ?? 10),
                            active: Boolean(plan.active),
                          },
                        })
                      }
                    >
                      Edit Limits &amp; Price
                    </button>
                    {!isFree && (
                      <button
                        type="button"
                        className="btn btn--sm"
                        disabled={busy}
                        onClick={() => togglePlanActive(plan)}
                      >
                        {plan.active ? 'Hide' : 'Publish'}
                      </button>
                    )}
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      </Card>

      {editingPlan && (
        <PlanEditorModal
          mode={editingPlan.mode}
          initial={editingPlan.plan}
          currencyCode={currencyCode}
          currencySymbol={currencySymbol}
          busy={busy}
          onClose={() => !busy && setEditingPlan(null)}
          onSave={(payload) => handleSavePlan(editingPlan.mode, payload)}
        />
      )}
    </div>
  )
}

function PlanEditorModal({
  mode,
  initial,
  currencyCode,
  currencySymbol,
  busy,
  onClose,
  onSave,
}) {
  const [form, setForm] = useState(initial)
  const [localError, setLocalError] = useState(null)
  const isCreate = mode === 'create'
  const isFree = form.code === 'FREE'

  function update(key, value) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  function submit(event) {
    event.preventDefault()
    setLocalError(null)
    const code = String(form.code || '')
      .trim()
      .toUpperCase()
    if (!code || !/^[A-Z0-9_-]{2,30}$/.test(code)) {
      setLocalError(
        'Plan code must be 2–30 characters (letters, numbers, underscores, or hyphens).',
      )
      return
    }
    if (!String(form.name || '').trim()) {
      setLocalError('Enter a display name for this plan.')
      return
    }
    const price = Number(form.monthlyPrice)
    if (Number.isNaN(price) || price < 0) {
      setLocalError('Monthly price must be 0 or greater.')
      return
    }
    const numericFields = [
      ['maxStudents', 'Max Students'],
      ['maxRooms', 'Max Exam Halls'],
      ['maxGenerationsPerMonth', 'Monthly Generations'],
      ['seatsPerGeneration', 'Seats per Generation'],
      ['storageLimitMb', 'Storage Limit (MB)'],
      ['maxFileSizeMb', 'Max File Size (MB)'],
    ]
    for (const [key, label] of numericFields) {
      const val = Number(form[key])
      if (!Number.isInteger(val) || val < 1) {
        setLocalError(`${label} must be a whole number of at least 1.`)
        return
      }
    }

    onSave({
      code,
      name: form.name.trim(),
      monthlyPrice: price,
      maxStudents: Number(form.maxStudents),
      maxRooms: Number(form.maxRooms),
      maxGenerationsPerMonth: Number(form.maxGenerationsPerMonth),
      seatsPerGeneration: Number(form.seatsPerGeneration),
      storageLimitMb: Number(form.storageLimitMb),
      maxFileSizeMb: Number(form.maxFileSizeMb),
      sortOrder: form.sortOrder === '' ? 10 : Number(form.sortOrder),
      active: isFree ? true : Boolean(form.active),
    })
  }

  return (
    <Modal
      wide
      title={
        isCreate
          ? 'Create New Subscription Tier'
          : `Edit Tier · ${initial.name} (${initial.code})`
      }
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button
            type="submit"
            form="plan-editor-form"
            className="btn btn--primary"
            disabled={busy}
          >
            {busy && <span className="spinner" />}
            {busy
              ? 'Saving…'
              : isCreate
                ? 'Create Tier'
                : 'Save Plan & Quotas'}
          </button>
        </>
      }
    >
      <form id="plan-editor-form" onSubmit={submit} className="stack">
        {localError && <Alert tone="error">{localError}</Alert>}

        <div className="grid grid--2">
          <Field
            label="Plan Code *"
            hint={
              isCreate
                ? 'Unique identifier (e.g. CAMPUS_PLUS, ANNUAL_PRO).'
                : 'Immutable tier identifier referenced by subscriptions.'
            }
          >
            <input
              type="text"
              value={form.code}
              disabled={!isCreate}
              onChange={(e) => update('code', e.target.value.toUpperCase())}
              placeholder="e.g. CAMPUS_PLUS"
              required
            />
          </Field>

          <Field
            label="Display Name *"
            hint="Shown on the homepage and client billing page."
          >
            <input
              type="text"
              value={form.name}
              onChange={(e) => update('name', e.target.value)}
              placeholder="e.g. Starter / Pro / University"
              required
            />
          </Field>
        </div>

        <div className="grid grid--2">
          <Field
            label={`Monthly Price (${currencyCode} ${currencySymbol}) *`}
            hint="Set to 0 for free / starter plans."
          >
            <input
              type="number"
              min="0"
              step="0.01"
              value={form.monthlyPrice}
              onChange={(e) => update('monthlyPrice', e.target.value)}
              required
            />
          </Field>

          <Field
            label="Catalog Display Order"
            hint="Lower numbers appear further left (e.g. 10, 20, 30, 40)."
          >
            <input
              type="number"
              value={form.sortOrder}
              onChange={(e) => update('sortOrder', e.target.value)}
            />
          </Field>
        </div>

        <div className="divider" />

        <div className="grid grid--2">
          <Field
            label="Max Students *"
            hint="Maximum students stored in the institution roster."
          >
            <input
              type="number"
              min="1"
              step="1"
              value={form.maxStudents}
              onChange={(e) => update('maxStudents', e.target.value)}
              required
            />
          </Field>

          <Field
            label="Max Exam Halls *"
            hint="Maximum configured examination rooms."
          >
            <input
              type="number"
              min="1"
              step="1"
              value={form.maxRooms}
              onChange={(e) => update('maxRooms', e.target.value)}
              required
            />
          </Field>

          <Field
            label="Generations per Month *"
            hint="Monthly seating arrangement runs per billing cycle."
          >
            <input
              type="number"
              min="1"
              step="1"
              value={form.maxGenerationsPerMonth}
              onChange={(e) => update('maxGenerationsPerMonth', e.target.value)}
              required
            />
          </Field>

          <Field
            label="Max Seats per Generation *"
            hint="Maximum students seated in a single arrangement run."
          >
            <input
              type="number"
              min="1"
              step="1"
              value={form.seatsPerGeneration}
              onChange={(e) => update('seatsPerGeneration', e.target.value)}
              required
            />
          </Field>

          <Field
            label="Total File Storage (MB) *"
            hint="Total storage quota for imported Excel rosters."
          >
            <input
              type="number"
              min="1"
              step="1"
              value={form.storageLimitMb}
              onChange={(e) => update('storageLimitMb', e.target.value)}
              required
            />
          </Field>

          <Field
            label="Max Single Upload Size (MB) *"
            hint="Largest individual spreadsheet upload allowed."
          >
            <input
              type="number"
              min="1"
              step="1"
              value={form.maxFileSizeMb}
              onChange={(e) => update('maxFileSizeMb', e.target.value)}
              required
            />
          </Field>
        </div>

        <label className="check" style={{ marginTop: 4 }}>
          <input
            type="checkbox"
            checked={isFree ? true : Boolean(form.active)}
            disabled={isFree}
            onChange={(e) => update('active', e.target.checked)}
          />
          <span>
            <strong>Show this tier in the public &amp; upgrade plan catalog</strong>
            {isFree && (
              <span className="small muted" style={{ display: 'block' }}>
                The default FREE tier remains active for new workspace signups, while all of its
                quota limits above can be customized freely.
              </span>
            )}
          </span>
        </label>
      </form>
    </Modal>
  )
}
