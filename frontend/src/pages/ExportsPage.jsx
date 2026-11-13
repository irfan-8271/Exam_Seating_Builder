import { useEffect, useState } from 'react'
import { download } from '../api/client'
import { exportApi, seatingApi } from '../api/endpoints'
import { Alert, Card } from '../components/ui'

/**
 * Downloadable Excel and PDF deliverables for the active seating plan.
 */
export default function ExportsPage() {
  const [scopeKey, setScopeKey] = useState('')
  const [scopes, setScopes] = useState([])
  const [busy, setBusy] = useState(null)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)

  useEffect(() => {
    Promise.all([
      seatingApi.rounds().catch(() => []),
      seatingApi.current({ page: 0, size: 200 }).catch(() => null),
    ]).then(([rounds, currentPlan]) => {
      const found = new Set()
      ;(rounds || []).forEach((r) => {
        if (r.scopeKey) found.add(r.scopeKey)
      })
      ;(currentPlan?.allocations || []).forEach((a) => {
        if (a.scopeKey) found.add(a.scopeKey)
      })
      setScopes([...found].sort())
    })
  }, [])

  async function grab(kind, path, filename) {
    setBusy(kind)
    setError(null)
    setNotice(null)
    try {
      await download(path, filename)
      setNotice(`${filename} downloaded.`)
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(null)
    }
  }

  const stamp = new Date().toISOString().slice(0, 10)
  const suffix = scopeKey ? `-${scopeKey.replace(/[^A-Za-z0-9._-]/g, '_')}` : ''

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Export</h1>
          <p>Download Excel workbooks, Hall Door Notices, and Invigilator Attendance Sheets.</p>
        </div>
      </div>

      {error && <Alert tone="error">{error}</Alert>}
      {notice && <Alert tone="success">{notice}</Alert>}

      {scopes.length > 0 && (
        <Card title="Exam round filter" subtitle="Leave empty to export every seated round.">
          <select
            value={scopeKey}
            onChange={(event) => setScopeKey(event.target.value)}
            style={{ maxWidth: 320 }}
          >
            <option value="">All rounds</option>
            {scopes.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </Card>
      )}

      <div className="grid grid--2">
        <Card
          title="Excel workbook (.xlsx)"
          subtitle="Dynamic Master List, visual 2D Hall Maps, and Cohort Colour Legend."
        >
          <p className="small muted">
            Includes every dynamic attribute from your institution’s roster, plus one visual 2D
            seating grid sheet per hall.
          </p>
          <button
            type="button"
            className="btn btn--primary"
            disabled={busy === 'excel'}
            onClick={() =>
              grab('excel', exportApi.workbookPath(scopeKey), `seating-plan${suffix}-${stamp}.xlsx`)
            }
          >
            {busy === 'excel' && <span className="spinner" />}
            Download .xlsx Workbook
          </button>
        </Card>

        <Card
          title="Door notices (PDF)"
          subtitle="Landscape A4 visual bench matrix per hall for notice boards."
        >
          <p className="small muted">
            Colour-coded desk cards with student IDs, cohort badges, and front-of-hall blackboard
            orientation.
          </p>
          <button
            type="button"
            className="btn btn--primary"
            disabled={busy === 'door'}
            onClick={() =>
              grab(
                'door',
                exportApi.doorNoticesPath(scopeKey),
                `door-notices${suffix}-${stamp}.pdf`,
              )
            }
          >
            {busy === 'door' && <span className="spinner" />}
            Download Door Notices
          </button>
        </Card>

        <Card
          title="Invigilator sheets (PDF)"
          subtitle="Portrait A4 hall-wise attendance rosters with signature columns."
        >
          <p className="small muted">
            Ready-to-print attendance rosters listing Student ID, Name, Cohort, Desk Coordinates,
            and Candidate Signature.
          </p>
          <button
            type="button"
            className="btn btn--primary"
            disabled={busy === 'invig'}
            onClick={() =>
              grab(
                'invig',
                exportApi.invigilatorSheetsPath(scopeKey),
                `invigilator-sheets${suffix}-${stamp}.pdf`,
              )
            }
          >
            {busy === 'invig' && <span className="spinner" />}
            Download Invigilator Sheets
          </button>
        </Card>
      </div>
    </>
  )
}
