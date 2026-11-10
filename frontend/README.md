# Exam Seating Builder — the frontend

A Vite + React application. It talks to the backend's `/api/v1` endpoints and
nothing else: there is no second API, no mock layer and no build step that
touches the server side.

Plain JavaScript (JSX), React 18, React Router 6 — no TypeScript, no CSS
framework, no state-management library. One stylesheet, written by hand.

---

## Running it

```bash
npm install
npm run dev
```

The dev server listens on **http://localhost:5173** and proxies `/api` to
**http://localhost:8080**. Start the backend first.

Point it somewhere else with an environment variable:

```bash
VITE_API_TARGET=http://localhost:8092 npm run dev
```

For a production build:

```bash
npm run build        # -> frontend/dist/
npm run preview      # serves the build locally
```

`frontend/dist/` is static — serve it from any web server and forward `/api`
to the backend. The application uses the browser's history API, so the server
must fall back to `index.html` for unknown paths.

---

## The screens

| Route | Screen |
|---|---|
| `/signin` | Sign in and register. The small link in the footer opens the administrator's sign-in. |
| `/` | The dashboard: what this institution has, what it may still do, the recent plans. |
| `/students` | The roster: add one at a time, or upload a sheet — with the identity conversation. |
| `/halls` | The halls: rows, benches per row, single or dual seater. |
| `/generate` | Who to seat, which halls, the difficulty, the group attributes. |
| `/plan` | The plan as it will look on the day. Click a student, then a seat, to move them. |
| `/exports` | The Excel workbook, the door notices, the invigilator sheets. |
| `/settings` | The institution's details, its plan, what it has used. |
| `/admin` | The platform administrator's view — every institution and account. |

---

## How it is put together

```
src/
  main.jsx              the entry point
  App.jsx               the routes and the guards
  styles.css            the whole look of the application
  api/
    client.js           fetch, the token, the error envelope
    endpoints.js        every backend call, in one place
  auth/
    AuthContext.jsx     the session: who is signed in, what they may do
  components/
    AppShell.jsx        the frame around every signed-in page
    ui.jsx              the small building blocks (Card, Table, Modal, …)
  pages/
    …one file per screen
```

**Every URL is built in `api/endpoints.js`** — no page builds a path by hand,
so the contract with the backend is visible in one file.

**The session** lives in `localStorage` and is sent as a bearer token. A 401
ends the session and returns to the sign-in screen.

**The identity conversation** is the heart of the roster upload. The backend
analyses the sheet and reports the columns it found; the screen shows them and
asks which one identifies a student. The answer is saved as the institution's
standing rule, so the next roster is read the same way.

**The group attributes** in the generate screen come from the roster itself —
"batch", "section", "hostel block", whatever the institution uses. Nothing is
hard-coded.

---

## The platform administrator

The administrator signs in with the same form. The link is a small one in the
footer of the sign-in page, so it does not disturb the ordinary user.

The account is created by the backend on startup from `PLATFORM_ADMIN_USERNAME`
and `PLATFORM_ADMIN_PASSWORD` (see `.env.example` in the repository root).
When the password is blank, no administrator exists and the screen stays empty.

The administrator can see every institution and every account, how much each
of them generates, and can move an institution to another plan and/or raise a
single limit above the plan's number — which is how a customer blocked by a
subscription limit gets unblocked.

---

## The smoke tests

`scripts/` holds two scripts that exercise the whole product against a running
backend. See `scripts/README.md`.
