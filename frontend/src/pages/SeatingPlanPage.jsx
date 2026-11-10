import { useCallback, useEffect, useMemo, useState } from 'react'
import { seatingApi, roomApi } from '../api/endpoints'
import { Alert, Badge, Card, EmptyState, Field, Loading, Modal } from '../components/ui'

/**
 * The seating plan, hall by hall.
 *
 * Clicking a seated student picks them up; clicking an empty seat puts them
 * down. The move is validated by the backend — the same rules the generator
 * uses — so a manual move can never break the plan's guarantees.
 */
export default function SeatingPlanPage() {
  const [plan, setPlan] = useState(null)
  const [rooms, setRooms] = useState([])
  const [rounds, setRounds] = useState([])
  const [subjectCode, setSubjectCode] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const [picked, setPicked] = useState(null)
  const [moving, setMoving] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [allocations, roomPage, roundList] = await Promise.all([
        seatingApi.allocations({ page: 0, size: 200, subjectCode: subjectCode || undefined }),
        roomApi.list({ page: 0, size: 200, sortBy: 'hallName' }),
        seatingApi.rounds().catch(() => []),
      ])
      setPlan(allocations)
      setRooms(roomPage?.content || [])
      setRounds(roundList || [])
    } catch (failure) {
      setError(failure.message)
    } finally {
      setLoading(false)
    }
  }, [subjectCode])

  useEffect(() => {
    load()
  }, [load])

  const allocations = plan?.content || []

  /** The seats of every hall, in the order the students should see them. */
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
        seats: byHall.get(room.hallName),
        allocations: byHall.get(room.hallName),
      }))
  }, [rooms, allocations])

  const subjects = useMemo(() => {
    const found = new Set()
    allocations.forEach((allocation) => {
      if (allocation.subjectCode) found.add(allocation.subjectCode)
    })
    return [...found].sort()
  }, [allocations])

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
        `${allocation.identity || allocation.name} was moved to ${room.hallName}, row ${benchRow}, bench ${benchCol}, seat ${slot}.`,
      )
      setPicked(null)
      load()
    } catch (failure) {
      setError(failure.message)
    } finally {
      setMoving(false)
    }
  }

  const currentRound = rounds.length ? rounds[rounds.length - 1] : null

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Seating plan</h1>
          <p>
            {allocations.length} student{allocations.length === 1 ? '' : 's'} seated
            {currentRound?.createdAt
              ? ` · generated ${new Date(currentRound.createdAt).toLocaleString()}`
              : ''}
          </p>
        </div>
        <div className="btn-row">
          <select
            value={subjectCode}
            onChange={(event) => setSubjectCode(event.target.value)}
            style={{ width: 'auto' }}
          >
            <option value="">All subjects</option>
            {subjects.map((subject) => (
              <option key={subject} value={subject}>
                {subject}
              </option>
            ))}
          </select>
        </div>
      </div>

        {error && <Alert tone="error">{error}</Alert>}
        {notice && <Alert tone="success">{notice}</Alert>}

        {picked && (
          <Alert tone="info" title={`${picked.identity || picked.name} is picked up.`}>
            Click an empty seat to put them down. Click the student again to cancel.
            <button
              type="button"
              className="btn btn--sm btn--ghost"
              style={{ marginLeft: 10 }}
              onClick={() => setPicked(null)}
            >
              Cancel the move
            </button>
          </Alert>
        )}

        {loading ? (
          <Loading label="Loading the plan…" />
        ) : allocations.length === 0 ? (
          <Card>
            <EmptyState title="There is no plan yet">
              Generate one first — the arrangement rules live on the “Generate” page.
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

/** One hall: its benches, and who sits where. */
function HallView({ room, allocations, picked, moving, onPick, onMove }) {
  const dual = room.benchType === 'DUAL_SEATER'

  /** seatKey → allocation */
  const seated = useMemo(() => {
    const map = new Map()
    allocations.forEach((allocation) => {
      map.set(`${allocation.benchRow}:${allocation.benchCol}:${allocation.slot}`, allocation)
    })
    return map
  }, [allocations])

  const rows = []
  for (let row = 1; row <= room.rows; row += 1) rows.push(row)

  return (
    <div className="hall">
      <div className="hall__head">
        <strong>{room.hallName}</strong>
        <span className="faint small">
          {room.rows} × {room.columns} benches · {dual ? 'dual seater' : 'single seater'}
        </span>
        <span className="spread" />
        <Badge>
          {allocations.length} seated
        </Badge>
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
                      const isPicked = picked && allocation && allocation.allocationId === picked.allocationId
                      if (!allocation) {
                        return (
                          <button
                            type="button"
                            key={slot}
                            className={`seat seat--empty ${picked ? 'seat--target' : ''}`}
                            disabled={!picked || moving}
                            onClick={() => picked && onMove(picked, room, row, column, slot)}
                            title={picked ? 'Put the student here' : 'Empty seat'}
                          >
                            {picked ? '+' : ''}
                          </button>
                        )
                      }
                      return (
                        <button
                          type="button"
                          key={slot}
                          className={`seat ${isPicked ? 'seat--selected' : ''}`}
                          onClick={() => onPick(isPicked ? null : allocation)}
                          title="Click to pick this student up"
                        >
                          <span className="seat__id">{allocation.identity || allocation.rollNo || '—'}</span>
                          <span className="seat__name">{allocation.name}</span>
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
