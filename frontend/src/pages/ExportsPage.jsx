import { useEffect, useState } from 'react'
import { download } from '../api/client'
import { exportApi, seatingApi } from '../api/endpoints'
import { Alert, Card, Loading } from '../components/ui'

/**
 * Taking the plan away: Excel for the office, PDF for the doors and the
 * invigilators.
 */
export default function ExportsPage() {
  const [subjectCode, setSubjectCode] = useState('')
  const [subjects, setSubjects] = useState([])
  const [busy, setBusy] = useState(null)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const [representations, setRepresentations] = useState([])

  useEffect(() => {
    seatingApi
      .allocations({ page: 0, size: 200 })
      .then((page) => {
        const found = new Set()
        ;(page?.content || []).forEach((allocation) => {
          if (allocation.subjectCode) found.add(allocation.subjectCode)
        })
        setSubjects([...found].sort())
      })
      .catch(() => {})
    exportApi
      .list()
      .then(setRepresentations)
      .catch(() => {})
  }, [])

  async function grab(kind, path, filename) {
    setBusy(kind)
    setError(null)
    setNotice(null)
    try {
      await download(path, filename)
      setNotice(`${filename} was downloaded.`)
    } catch (failure) {
      setError(failure.message)
    } finally {
      setBusy(null)
    }
  }

  const stamp = new Date().toISOString().slice(0, 10)
  const suffix = subjectCode ? `-${subjectCode}` : ''

  return (
    <>
      <header className="topbar">
        <div className="topbar__title">
          <h1>Export</h1>
          <p>Take the seating plan away as Excel or PDF.</p>
        </div>
      </header>

      <div className="content">
        {error && <Alert tone="error">{error}</Alert>}
        {notice && <Alert tone="success">{notice}</Alert>}

        <Card title="Which paper" subtitle="Leave empty to export everything on the plan.">
          <select
            value={subjectCode}
            onChange={(event) => setSubjectCode(event.target.value)}
            style={{ maxWidth: 320 }}
          >
            <option value="">All subjects</option>
            {subjects.map((subject) => (
              <option key={subject} value={subject}>
                {subject}
              </option>
            ))}
          </select>
        </Card>

        <div className="grid grid--2">
          <Card title="Excel workbook" subtitle="The master list, the hall-wise list and the legend.">
            <p className="small muted">
              The workbook keeps the format the office already expects — one sheet with
              every seat, one sheet per hall, and a legend sheet that explains the marks.
            </p>
            <button
              type="button"
              className="btn btn--primary"
              disabled={busy === 'excel'}
              onClick={() =>
                grab('excel', '/api/v1/exports/excel', `seating-plan${suffix}-${stamp}.xlsx`)
              }
            >
              {busy === 'excel' && <span className="spinner" />}
              Download the .xlsx
            </button>
          </Card>

          <Card title="Door notices (PDF)" subtitle="One sheet per hall, to pin on the door.">
            <p className="small muted">
              Lists the students of each hall with their seat, ready to print.
            </p>
            <button
              type="button"
              className="btn btn--primary"
              disabled={busy === 'door'}
              onClick={() =>
                grab(
                  'door',
                  `/api/v1/exports/pdf/door-notices${subjectCode ? `?subjectCode=${encodeURIComponent(subjectCode)}` : ''}`,
                  `door-notices${suffix}-${stamp}.pdf`,
                )
              }
            >
              {busy === 'door' && <span className="spinner" />}
              Download the door notices
            </button>
          </Card>

          <Card title="Invigilator sheets (PDF)" subtitle="Attendance sheets, one per hall.">
            <p className="small muted">
              A sign-in sheet for each hall, with the students of that hall.
            </p>
            <button
              type="button"
              className="btn btn--primary"
              disabled={busy === 'invigilator'}
              onClick={() =>
                grab(
                  'invigilator',
                  `/api/v1/exports/pdf/invigilator${subjectCode ? `?subjectCode=${encodeURIComponent(subjectCode)}` : ''}`,
                  `invigilator-sheets${suffix}-${stamp}.pdf`,
                )
              }
            >
              {busy === 'invigilator' && <span className="spinner" />}
              Download the invigilator sheets
            </button>
          </Card>

          {representations.length > 0 && (
            <Card title="What the backend offers">
              <ul style={{ margin: 0, paddingLeft: 18 }}>
                {representations.map((item, index) => (
                  <li key={index} className="small">
                    {typeof item === 'string' ? item : JSON.stringify(item)}
                  </li>
                ))}
              </ul>
            </Card>
          )}
        </div>
      </div>
    </>
  )
}
