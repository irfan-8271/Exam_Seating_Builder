import { useCallback, useEffect, useMemo, useState } from 'react'
import { roomApi, seatingApi } from '../api/endpoints'
import { Alert, Badge, Card, EmptyState, Loading } from '../components/ui'

const COHORT_SWATCHES = [
  '#e0f2fe',
  '#dcfce7',
  '#fef3c7',
  '#fce7f3',
  '#ede9fe',
  '#ffedd5',
  '#cffafe',
  '#fae8ff',
]

function cohortSwatch(label) {
  if (!label) return undefined
  let hash = 0
  for (let i = 0; i < label.length; i += 1) {
    hash = (hash * 31 + label.charCodeAt(i)) | 0
  }
  return COHORT_SWATCHES[Math.abs(hash) % COHORT_SWATCHES.length]
}

function strictnessLabel(strictness) {
  if (strictness === 'EXTREME' || strictness === 'HARD' || strictness === 'RIGID') {
    return 'Extreme / Rigid'
  }
  if (strictness === 'INTERMEDIATE') return 'Intermediate'
  if (strictness === 'NORMAL') return 'Normal'
  return strictness || null
}

export default function SeatingPlanPage() {
  const [plan, setPlan] = useState(null)
  const [rooms, setRooms] = useState([])
  const [rounds, setRounds] = useState([])
  const [scopeKey, setScopeKey] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const [picked, setPicked] = useState(null)
  const [moving, setMoving] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [currentPlan, roomPage, roundList] = await Promise.all([
        seatingApi.currentAll({ scopeKey: scopeKey || undefined }),
        roomApi.listAll({ sortBy: 'hallName' }),
        seatingApi.rounds().catch(() => []),
      ])
      setPlan(currentPlan)
      setRooms(roomPage?.content || [])
      setRounds(roundList || [])
    } catch (failure) {
      setError(failure.message)
    } finally {
      setLoading(false)
    }
  }, [scopeKey])

  useEffect(() => {
    load()
  }, [load])

  const allocations = plan?.allocations || []
  const totalSeated = plan?.totalElements ?? plan?.seated ?? allocations.length

  const halls = useMemo(() => {
    const byHall = new Map()
    allocations.forEach((allocation) => {
      if (!byHall.has(allocation.hallName)) byHall.set(allocation.hallName, [])
      byHall.get(allocation.hallName).push(allocation)
    })
    return rooms
      .filter((room) => byHall.has(room.hallName))
      .map((room) => ({
        room,
        allocations: byHall.get(room.hallName),
      }))
  }, [rooms, allocations])

  const availableScopes = useMemo(() => {
    const found = new Set()
    rounds.forEach((r) => {
      if (r.scopeKey) found.add(r.scopeKey)
    })
    allocations.forEach((a) => {
      if (a.scopeKey) found.add(a.scopeKey)
    })
    return [...found].sort()
  }, [rounds, allocations])

  async function moveTo(allocation, room, benchRow, benchCol, slot) {
    setMoving(true)
    setError(null)
    try {
      await seatingApi.move(allocation.allocationId, {
        roomId: room.id,
        benchRow,
        benchCol,
        slot,
      })
      setNotice(
        `${allocation.identity || allocation.name} moved to ${room.hallName}, row ${benchRow}, bench ${benchCol}, seat ${slot}.`,
      )
      setPicked(null)
      load()
    } catch (failure) {
      setError(failure.message)
    } finally {
      setMoving(false)
    }
  }

  const currentRound = useMemo(() => {
    if (!rounds.length) return null
    if (scopeKey) {
      const matched = [...rounds]
        .reverse()
        .find((r) => (r.scopeKey || '').toUpperCase() === scopeKey.toUpperCase())
      if (matched) return matched
    }
    return rounds[rounds.length - 1]
  }, [rounds, scopeKey])

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Seating plan</h1>
          <p>
            {totalSeated} student{totalSeated === 1 ? '' : 's'} seated
            {currentRound?.createdAt
              ? ` · generated ${new Date(currentRound.createdAt).toLocaleString()}`
              : ''}
          </p>
          {currentRound && (
            <div className="row" style={{ gap: 8, marginTop: 6, flexWrap: 'wrap' }}>
              {currentRound.strictness && (
                <Badge tone="primary">
                  Difficulty: {strictnessLabel(currentRound.strictness)}
                </Badge>
              )}
              {currentRound.groupAttributes?.length > 0 && (
                <Badge tone="info">
                  Cohort: {currentRound.groupAttributes.join(' + ')} ({currentRound.groupMatchMode || 'ALL'})
                </Badge>
              )}
              {currentRound.scopeKey && (
                <Badge>
                  Round: {currentRound.scopeKey}
                </Badge>
              )}
            </div>
          )}
        </div>
        {availableScopes.length > 0 && (
          <div className="btn-row">
            <select
              value={scopeKey}
              onChange={(event) => setScopeKey(event.target.value)}
              style={{ width: 'auto' }}
            >
              <option value="">All exam rounds</option>
              {availableScopes.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>

      {error && <Alert tone="error">{error}</Alert>}
      {notice && <Alert tone="success">{notice}</Alert>}

      {picked && (
        <Alert
          tone="info"
          title={`${picked.identity || picked.name}${picked.cohort ? ` (${picked.cohort})` : ''} selected for move.`}
        >
          Click any empty seat to place them. All separation constraints of the round are enforced.
          <button
            type="button"
            className="btn btn--sm btn--ghost"
            style={{ marginLeft: 10 }}
            onClick={() => setPicked(null)}
          >
            Cancel
          </button>
        </Alert>
      )}

      {loading ? (
        <Loading label="Loading seating plan…" />
      ) : allocations.length === 0 ? (
        <Card>
          <EmptyState title="No seating plan generated yet">
            Head to the “Generate” page to create your seating arrangement.
          </EmptyState>
        </Card>
      ) : (
        halls.map((hall) => (
          <HallView
            key={hall.room.id}
            room={hall.room}
            allocations={hall.allocations}
            picked={picked}
            moving={moving}
            onPick={setPicked}
            onMove={moveTo}
          />
        ))
      )}
    </>
  )
}

function HallView({ room, allocations, picked, moving, onPick, onMove }) {
  const dual = room.benchType === 'DUAL_SEATER'

  const seated = useMemo(() => {
    const map = new Map()
    allocations.forEach((allocation) => {
      map.set(`${allocation.benchRow}:${allocation.benchCol}:${allocation.slot}`, allocation)
    })
    return map
  }, [allocations])

  const rows = Array.from({ length: room.rows }, (_, idx) => idx + 1)

  return (
    <div className="hall">
      <div className="hall__head">
        <strong>{room.hallName}</strong>
        <span className="faint small">
          {room.rows} × {room.columns} benches · {dual ? 'dual seater' : 'single seater'}
        </span>
        <span className="spread" />
        <Badge>{allocations.length} seated</Badge>
      </div>
      <div className="hall__grid">
        {rows.map((row) => (
          <div className="bench-row" key={row}>
            {Array.from({ length: room.columns }, (_, columnIndex) => {
              const column = columnIndex + 1
              const seats = dual ? [1, 2] : [1]
              return (
                <div className="bench" key={column}>
                  <div className="bench__label">
                    R{row} · B{column}
                  </div>
                  <div className="seats">
                    {seats.map((slot) => {
                      const allocation = seated.get(`${row}:${column}:${slot}`)
                      const isPicked =
                        picked && allocation && allocation.allocationId === picked.allocationId
                      if (!allocation) {
                        return (
                          <button
                            type="button"
                            key={slot}
                            className={`seat seat--empty ${picked ? 'seat--target' : ''}`}
                            disabled={!picked || moving}
                            onClick={() => picked && onMove(picked, room, row, column, slot)}
                            title={picked ? 'Move student here' : 'Empty seat'}
                          >
                            {picked ? '+' : ''}
                          </button>
                        )
                      }
                      const cohortText = allocation.cohort || ''
                      return (
                        <button
                          type="button"
                          key={slot}
                          className={`seat ${isPicked ? 'seat--selected' : ''}`}
                          style={
                            !isPicked && cohortText
                              ? {
                                  borderLeft: '4px solid #64748b',
                                  backgroundColor: cohortSwatch(cohortText),
                                }
                              : undefined
                          }
                          onClick={() => onPick(isPicked ? null : allocation)}
                          title={`${allocation.identity || ''} — ${allocation.name}${
                            cohortText ? ` (${cohortText})` : ''
                          }`}
                        >
                          <span className="seat__id">{allocation.identity || '—'}</span>
                          <span className="seat__name">{allocation.name}</span>
                          {cohortText && (
                            <span
                              className="faint small"
                              style={{ display: 'block', fontSize: '0.7rem', marginTop: 2 }}
                            >
                              {cohortText}
                            </span>
                          )}
                        </button>
                      )
                    })}
                  </div>
                </div>
              )
            })}
          </div>
        ))}
      </div>
    </div>
  )
}
