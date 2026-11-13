import { useMemo, useState } from 'react'
import { adminApi } from '../../api/endpoints'
import {
  Alert,
  Badge,
  Field,
  Modal,
  Table,
  bytes,
  formatMoney,
  moment,
  shortDate,
} from '../../components/ui'
import { IconClock, IconSearch } from '../../components/Icons'

const LIMIT_FIELDS = [
  { key: 'maxStudents', label: 'Max Students', unit: '' },
  { key: 'maxRooms', label: 'Max Exam Halls', unit: '' },
  { key: 'maxGenerationsPerMonth', label: 'Plans per Month', unit: '' },
  { key: 'seatsPerGeneration', label: 'Seats per Plan', unit: '' },
  { key: 'storageLimitMb', label: 'Storage Quota', unit: 'MB' },
  { key: 'maxFileSizeMb', label: 'Max Upload File', unit: 'MB' },
]

function addDaysIsoDate(days) {
  const d = new Date()
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

function toDateInputValue(instantValue) {
  if (!instantValue) return ''
  const d = new Date(instantValue)
  if (Number.isNaN(d.getTime())) return ''
  return d.toISOString().slice(0, 10)
}

/**
 * Every workspace: its plan, its usage and the permanent or promotional limits
 * it works under.
 */
export default function WorkspacesTab({
  institutions,
  plans,
  paymentConfig,
  onChanged,
}) {
  const [search, setSearch] = useState('')
  const [editing, setEditing] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)

  const currencySymbol =
    paymentConfig?.currencySymbol || plans?.[0]?.currencySymbol || '₹'
  const currencyCode =
    paymentConfig?.currencyCode || plans?.[0]?.currencyCode || 'INR'

  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase()
    if (!needle) return institutions
    return institutions.filter(
      (institution) =>
        institution.name?.toLowerCase().includes(needle) ||
        institution.slug?.toLowerCase().includes(needle) ||
        institution.contactEmail?.toLowerCase().includes(needle) ||
        institution.planName?.toLowerCase().includes(needle),
    )
  }, [institutions, search])

  async function run(action, successMessage) {
    setError(null)
    setNotice(null)
    setBusy(true)
    try {
      await action()
      if (successMessage) setNotice(successMessage)
      await onChanged()
    } catch (failure) {
      setError(failure.message || 'The change could not be saved')
    } finally {
      setBusy(false)
    }
  }

  const columns = [
    {
      key: 'name',
      title: 'Workspace',
      render: (institution) => (
        <div style={{ minWidth: 0 }}>
          <div className="user-cell__name">{institution.name}</div>
          <div className="user-cell__meta">
            {institution.contactEmail || 'No address'} · {institution.usersCount}{' '}
            {institution.usersCount === 1 ? 'account' : 'accounts'}
          </div>
        </div>
      ),
    },
    {
      key: 'plan',
      title: 'Plan & Overrides',
      render: (institution) => (
        <div>
          <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
            <Badge tone="brand">{institution.planName}</Badge>
            {institution.overrideActive && (
              <Badge tone="ok">
                {institution.overrideExpiresAt ? 'Promo Boost' : 'Custom Limits'}
              </Badge>
            )}
            {institution.overrideExpired && (
              <Badge tone="warn">Promo Expired</Badge>
            )}
          </div>
          {institution.overrideActive && institution.overrideExpiresAt && (
            <div className="small faint" style={{ marginTop: 3 }}>
              Until {shortDate(institution.overrideExpiresAt)}
            </div>
          )}
          {institution.overrideActive && institution.overrideReason && (
            <div
              className="small muted"
              style={{
                marginTop: 2,
                maxWidth: 210,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
              title={institution.overrideReason}
            >
              {institution.overrideReason}
            </div>
          )}
        </div>
      ),
    },
    {
      key: 'students',
      title: 'Students',
      numeric: true,
      render: (institution) => (
        <span className="nowrap">
          {institution.studentsUsed} / {institution.studentsLimit}
        </span>
      ),
    },
    {
      key: 'rooms',
      title: 'Halls',
      numeric: true,
      render: (institution) => (
        <span className="nowrap">
          {institution.roomsUsed} / {institution.roomsLimit}
        </span>
      ),
    },
    {
      key: 'generations',
      title: 'Plans',
      numeric: true,
      render: (institution) => (
        <span className="nowrap">
          {institution.generationsUsed} / {institution.generationsLimit}
        </span>
      ),
    },
    {
      key: 'storage',
      title: 'Storage',
      numeric: true,
      render: (institution) => (
        <span className="nowrap small muted">
          {bytes(institution.storageUsedBytes)} / {bytes(institution.storageLimitBytes)}
        </span>
      ),
    },
    {
      key: 'status',
      title: 'Status',
      render: (institution) => (
        <Badge tone={institution.status === 'ACTIVE' ? 'ok' : 'bad'}>
          {institution.status}
        </Badge>
      ),
    },
    {
      key: 'actions',
      title: '',
      render: (institution) => (
        <div className="row nowrap" style={{ justifyContent: 'flex-end' }}>
          <button
            type="button"
            className="btn btn--sm"
            onClick={() => setEditing(institution)}
          >
            Manage
          </button>
        </div>
      ),
    },
  ]

  return (
    <div className="stack">
      {error && <Alert tone="error">{error}</Alert>}
      {notice && <Alert tone="success">{notice}</Alert>}

      <section className="card">
        <div className="toolbar">
          <div style={{ position: 'relative', flex: '1 1 240px', maxWidth: 340 }}>
            <span
              style={{
                position: 'absolute',
                left: 10,
                top: '50%',
                transform: 'translateY(-50%)',
                color: 'var(--text-faint)',
                pointerEvents: 'none',
                display: 'inline-flex',
              }}
            >
              <IconSearch width={16} height={16} />
            </span>
            <input
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Search by name, handle, plan or address"
              style={{ paddingLeft: 34 }}
            />
          </div>
          <span className="toolbar__spacer" />
          <span className="muted small">
            {rows.length} of {institutions.length} workspaces
          </span>
        </div>

        <Table columns={columns} rows={rows} empty="No workspace matches that search." />
      </section>

      {editing && (
        <ManageWorkspaceDialog
          institution={editing}
          plans={plans}
          currencyCode={currencyCode}
          currencySymbol={currencySymbol}
          busy={busy}
          onClose={() => !busy && setEditing(null)}
          onSave={(body) =>
            run(async () => {
              await adminApi.changeSubscription(editing.id, body)
              setEditing(null)
            }, `Updated plan & custom limits for ${editing.name}.`)
          }
          onToggleStatus={() =>
            run(async () => {
              const next = editing.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'
              await adminApi.changeStatus(editing.id, next)
              setEditing(null)
            }, `Workspace ${editing.name} set to ${editing.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'}.`)
          }
        />
      )}
    </div>
  )
}

/** The "manage a workspace" dialog: its plan, permanent overrides, and promotional boosts. */
function ManageWorkspaceDialog({
  institution,
  plans,
  currencyCode,
  currencySymbol,
  busy,
  onClose,
  onSave,
  onToggleStatus,
}) {
  const [planCode, setPlanCode] = useState(institution.planCode)
  const [limits, setLimits] = useState({
    maxStudents: institution.overrides?.maxStudents ?? '',
    maxRooms: institution.overrides?.maxRooms ?? '',
    maxGenerationsPerMonth: institution.overrides?.maxGenerationsPerMonth ?? '',
    storageLimitMb: institution.overrides?.storageLimitMb ?? '',
    maxFileSizeMb: institution.overrides?.maxFileSizeMb ?? '',
    seatsPerGeneration: institution.overrides?.seatsPerGeneration ?? '',
  })
  const [expiryDate, setExpiryDate] = useState(
    toDateInputValue(institution.overrideExpiresAt),
  )
  const [overrideReason, setOverrideReason] = useState(
    institution.overrideReason || '',
  )

  const selectablePlans = useMemo(() => {
    return (plans || []).filter(
      (p) => p.active !== false || p.code === institution.planCode,
    )
  }, [plans, institution.planCode])

  const plan = (plans || []).find((entry) => entry.code === planCode)

  const hasAnyOverrideInput = LIMIT_FIELDS.some(
    ({ key }) => limits[key] !== '' && limits[key] !== null && limits[key] !== undefined,
  )

  const effectiveStudents =
    limits.maxStudents !== '' && limits.maxStudents !== null
      ? Number(limits.maxStudents)
      : (plan?.maxStudents ?? institution.studentsLimit)
  const effectiveRooms =
    limits.maxRooms !== '' && limits.maxRooms !== null
      ? Number(limits.maxRooms)
      : (plan?.maxRooms ?? institution.roomsLimit)

  const overSoftCap =
    institution.studentsUsed > effectiveStudents ||
    institution.roomsUsed > effectiveRooms

  function set(key, value) {
    setLimits((current) => ({ ...current, [key]: value }))
  }

  function clearOverrides() {
    setLimits({
      maxStudents: '',
      maxRooms: '',
      maxGenerationsPerMonth: '',
      storageLimitMb: '',
      maxFileSizeMb: '',
      seatsPerGeneration: '',
    })
    setExpiryDate('')
    setOverrideReason('')
  }

  return (
    <Modal
      wide
      title={`Manage Workspace · ${institution.name}`}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn--danger"
            onClick={onToggleStatus}
            disabled={busy}
          >
            {institution.status === 'ACTIVE' ? 'Suspend Workspace' : 'Restore Workspace'}
          </button>
          <button
            type="button"
            className="btn btn--primary"
            disabled={busy}
            onClick={() => {
              const parsedLimits = {}
              let anySet = false
              LIMIT_FIELDS.forEach(({ key }) => {
                const raw = limits[key]
                if (raw === '' || raw === null || raw === undefined) {
                  parsedLimits[key] = null
                } else {
                  parsedLimits[key] = Number(raw)
                  anySet = true
                }
              })
              const expiresIso =
                anySet && expiryDate ? `${expiryDate}T23:59:59Z` : null
              onSave({
                planCode,
                limits: parsedLimits,
                overrideExpiresAt: expiresIso,
                overrideReason: anySet ? overrideReason.trim() || null : null,
              })
            }}
          >
            {busy ? 'Saving…' : 'Save Plan & Overrides'}
          </button>
        </>
      }
    >
      <div className="stack">
        <div className="row row--between" style={{ flexWrap: 'wrap', gap: 8 }}>
          <div className="muted small">
            Created {moment(institution.createdAt)} · handle{' '}
            <code>{institution.slug}</code>
          </div>
          <div className="small muted">
            Current usage: <strong>{institution.studentsUsed}</strong> students ·{' '}
            <strong>{institution.roomsUsed}</strong> halls ·{' '}
            <strong>{institution.generationsUsed}</strong> plans
          </div>
        </div>

        {institution.overrideExpired && (
          <Alert tone="warning" title="Previous Promotional Override Expired:">
            The earlier custom override expired on{' '}
            <strong>{shortDate(institution.overrideExpiresAt)}</strong>. This workspace
            is currently operating on its base <strong>{institution.planName}</strong>{' '}
            plan limits. Clear the fields below or pick a new future date to extend it.
          </Alert>
        )}

        <Field
          label="Base Subscription Plan"
          hint="The baseline tier this institution subscribes to."
        >
          <select
            value={planCode}
            onChange={(event) => setPlanCode(event.target.value)}
          >
            {selectablePlans.map((entry) => {
              const priceLabel =
                Number(entry.monthlyPrice || 0) === 0
                  ? 'Free'
                  : `${formatMoney(entry.monthlyPrice, {
                      symbol: currencySymbol,
                      code: currencyCode,
                    })}/mo`
              return (
                <option key={entry.code} value={entry.code}>
                  {entry.name} ({priceLabel} · {entry.maxStudents.toLocaleString()} students ·{' '}
                  {entry.maxRooms} halls)
                </option>
              )
            })}
          </select>
        </Field>

        <div className="divider" />

        <div className="row row--between" style={{ flexWrap: 'wrap', gap: 8 }}>
          <div>
            <div className="strong">Client-Specific Quota Overrides (Optional)</div>
            <p className="muted small" style={{ margin: 0 }}>
              Grant extra capacity or custom caps for this institution without changing
              the global plan. Leave any box blank to inherit the base plan&apos;s limit.
            </p>
          </div>
          {hasAnyOverrideInput && (
            <button
              type="button"
              className="btn btn--sm"
              onClick={clearOverrides}
              disabled={busy}
            >
              Reset All to Base Plan
            </button>
          )}
        </div>

        <div className="grid grid--2">
          {LIMIT_FIELDS.map((field) => {
            const planValue = plan ? plan[field.key] : undefined
            return (
              <Field
                key={field.key}
                label={`${field.label}${field.unit ? ` (${field.unit})` : ''}`}
                hint={
                  planValue !== undefined
                    ? `Base plan default: ${Number(planValue).toLocaleString()}${
                        field.unit ? ` ${field.unit}` : ''
                      }`
                    : undefined
                }
              >
                <input
                  type="number"
                  min="1"
                  value={limits[field.key]}
                  onChange={(event) => set(field.key, event.target.value)}
                  placeholder={planValue !== undefined ? String(planValue) : ''}
                />
              </Field>
            )
          })}
        </div>

        <div className="promo-box">
          <div className="row row--between" style={{ flexWrap: 'wrap', gap: 8, marginBottom: 10 }}>
            <div className="row" style={{ gap: 6 }}>
              <IconClock width={16} height={16} />
              <strong>Temporary / Promotional Override Duration</strong>
            </div>
            <div className="promo-preset-row">
              <button
                type="button"
                className={`btn btn--sm ${!expiryDate ? 'btn--primary' : ''}`}
                onClick={() => setExpiryDate('')}
              >
                Permanent
              </button>
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => setExpiryDate(addDaysIsoDate(7))}
              >
                +7 Days
              </button>
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => setExpiryDate(addDaysIsoDate(15))}
              >
                +15 Days
              </button>
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => setExpiryDate(addDaysIsoDate(30))}
              >
                +30 Days
              </button>
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => setExpiryDate(addDaysIsoDate(90))}
              >
                +90 Days
              </button>
            </div>
          </div>

          <div className="grid grid--2">
            <Field
              label="Override Valid Through Date (Optional)"
              hint="Leave empty for a permanent override. Automatically reverts to base plan after this date."
            >
              <input
                type="date"
                value={expiryDate}
                onChange={(e) => setExpiryDate(e.target.value)}
              />
            </Field>

            <Field
              label="Promotion / Override Note"
              hint="Shown to operators and on the client's quota summary."
            >
              <input
                type="text"
                maxLength={300}
                value={overrideReason}
                onChange={(e) => setOverrideReason(e.target.value)}
                placeholder="e.g. Semester Exam Promotional Boost (30 days)"
              />
            </Field>
          </div>
        </div>

        {overSoftCap && (
          <Alert tone="warning" title="Soft-Cap Notice:">
            This workspace currently holds <strong>{institution.studentsUsed}</strong>{' '}
            students and <strong>{institution.roomsUsed}</strong> halls, which exceeds
            the configured quota ({effectiveStudents} students / {effectiveRooms} halls).
            Existing data will remain intact, and new additions will be paused until
            usage is within quota.
          </Alert>
        )}
      </div>
    </Modal>
  )
}
