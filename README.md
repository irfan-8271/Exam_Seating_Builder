# Examination Seating Arrangement Builder

A Spring Boot application that generates anti-cheating exam seating plans. Students are
separated by a **configurable conflict key** (which attributes make two students
"classmates"), with **difficulty levels** controlling how far apart classmates must sit,
across an M×N grid of SINGLE_SEATER or DUAL_SEATER benches per hall.

**Stack:** Spring Boot 3.5 · Java 17 · MySQL 8 · Hibernate (JPA) · Apache POI (Excel) · OpenPDF (PDF)

---

## Quick start

```bash
# 1. MySQL must be running. DB_PASSWORD is REQUIRED — no credential is baked into the repo:
export DB_USERNAME=root
export DB_PASSWORD=yourpassword
# optional: export DB_URL='jdbc:mysql://localhost:3306/examseating?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true'
# optional: export SHOW_SQL=true   (SQL logging is off by default)
# optional: export SEED_USERS=true (create demo accounts; off by default)

# 2. Run
mvn spring-boot:run

# 3. Open http://localhost:8080        (bundled UI)
#    Open http://localhost:8080/openapi.yaml (machine-readable API contract)
```

Optionally `export SEED_DEMO=true` to seed demo rooms/students and a first seating round.
Demo **accounts** are only created when `SEED_USERS=true`; change their passwords before
exposing the service.

## Tests

```bash
mvn test
```

The engine tests cover gap semantics, difficulty-level density bounds, configurable
grouping keys, case-insensitive grouping, and isolation-flag parity between the two
solver paths. Import tests cover per-row transaction isolation.

---

## REST API (v1)

The primary API is resource-oriented under `/api/v1`. The machine-readable
[OpenAPI 3 contract](src/main/resources/static/openapi.yaml) is also served at
`/openapi.yaml` when the app is running.

| Resource | Endpoints |
|---|---|
| Students | `GET/POST /api/v1/students`, `GET/PUT/PATCH/DELETE /api/v1/students/{id}`, `POST /api/v1/students/imports` |
| Rooms | `GET/POST /api/v1/rooms`, `GET/PUT/PATCH/DELETE /api/v1/rooms/{id}`, `GET /api/v1/rooms/{id}/benches`, `POST /api/v1/rooms/imports` |
| Seating plans | `GET/POST/DELETE /api/v1/seating-plans`, `GET /api/v1/seating-plans/allocations` |
| Exports | `GET /api/v1/exports`, `GET /api/v1/exports/excel`, `GET /api/v1/exports/pdf/door-notices`, `GET /api/v1/exports/pdf/invigilator` |

Conventions:

- Collection `GET`s return a `PageResponse` envelope (`content`, `page`, `size`,
  `totalElements`, `totalPages`, `first`, `last`) and accept `page` (0-based),
  `size` (1–200), resource-specific filters, `sortBy` and `direction`.
- `POST` returns **201 Created** with a `Location` header; `DELETE` returns **204**.
- `PATCH` accepts only whitelisted fields; unknown/immutable fields are a 400.
- Every failure returns the same `ErrorResponse` envelope: `timestamp`, `status`,
  `code`, `error`, `message`, `path`, `details`. Typical codes: `VALIDATION_FAILED`,
  `MALFORMED_JSON`, `INVALID_PARAMETER`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`,
  `UNSUPPORTED_MEDIA_TYPE`, `CONFLICT`, `SEATING_ERROR`, `PAYLOAD_TOO_LARGE`.
- `POST /api/v1/seating-plans` with `clearExisting=false` **requires** `subjectCode`.
  Seats already used by other papers are passed to the engine as pre-occupied,
  so two rounds never collide on the same `(bench, slot)`.

**Legacy aliases:** all original `/api/students`, `/api/rooms`, `/api/seating/*`,
`/api/export/*`, `/api/mock/*` and `/api/auth/*` paths remain available and keep their
original response shapes so the bundled SPA continues to work. They are marked
`@Deprecated` in code and `deprecated: true` in the OpenAPI contract; new integrations
should use `/api/v1`.

---

## Difficulty levels

| Level | dH | dV | dD | Semantics |
|---|---|---|---|---|
| **BASIC** (alias `NORMAL`) | 1 | 0 | 0 | Bench-mate + side-by-side separation |
| **INTERMEDIATE** | 1 | 1 | 0 | + row separation (staggered packing) |
| **ADVANCED** (alias `EXTREME`) | 1 | 1 | 1 | Full multi-directional (Moore neighbourhood) |

Custom gaps can be sent explicitly (`horizontalGap`, `verticalGap`, `diagonalGap`) and
override the preset. A gap of `0` disables that axis.

**Gap metric (unified, both solver paths):**
- **Horizontal dH** — physical seats apart in the same row (`(col-1)*seats + slot`).
  Bench-mates are one step apart: the conflict key applies to them, plus the dual-seater
  pairing rule.
- **Vertical dV** — bench rows apart in the same bench column.
- **Diagonal dD** — row distance `> dD` AND bench-column distance `> dD` for
  conflicting students in different rows and bench columns.
- **Horizontal department isolation** (optional, on by default) — horizontally adjacent
  seats may never share a department regardless of the key.

## Configurable identification, grouping and separation

Nothing about classification is hardcoded. Students carry free-form attributes
(`department`, `course`, `yearSession` — schools may store class/section there, colleges a
department/batch, universities a programme/semester). The request chooses:

- `groupAttributes`: any subset of `DEPARTMENT`, `COURSE`, `YEAR_SESSION`
- `groupMatchMode`: `ALL` (share every attribute = classmates) or `ANY` (share at least
  one = strict isolation)
- legacy preset via `conflictScope` when `groupAttributes` is omitted

Comparison is case-insensitive and whitespace-tolerant. Student **identity** is the
roll/registration number (the label is institution-specific; uniqueness is enforced per
roll number).

## Subject codes are optional

`subjectCode` is optional metadata, never a seating factor. When present it can filter a
round (`POST /api/v1/seating-plans` with `subjectCode`), scope exports
(`?subjectCode=...`) and preserve other papers' plans (`clearExisting=false` requires a
`subjectCode` filter).

## Input files

Excel files are matched by header name (aliases accepted: "Roll No.", "Reg No",
"Registration Number", ...). Only **Name** and **Roll No** are required for students:

```
STUDENTS.xlsx : Name | Roll No | [Department] | [Course] | [Year/Session] | [Subject Code]
ROOMS.xlsx    : Hall Name | Rows | Columns | Bench Type   (SINGLE_SEATER | DUAL_SEATER)
```

Each row imports in its own transaction: a bad row (e.g. duplicate roll number) is
reported in the result and never rolls back the good rows.

## Security

`app.security.mode` (env `SECURITY_MODE`):

- `open` (default) — no authentication; for demos and testing. A warning is logged at startup.
- `enforced` — HTTP Basic for every API call, with the role model
  (`ADMIN` / `EXAM_CELL` / `INVIGILATOR`) enforced on controller methods. Browsers prompt
  for credentials, including on PDF/Excel downloads.

Demo accounts are **not** seeded by default. Set `SEED_USERS=true` for a local demo
(`DataSeeder`); the seed uses BCrypt hashes and no longer logs passwords. The HTTP Basic
filter chain is stateless, so CSRF protection is disabled — re-enable it if cookie-based
authentication is ever introduced. `/openapi.yaml` is publicly readable in both modes.

## Outputs

- **Excel workbook** — master list, per-hall bench maps (department-colored), legend.
  Sheet names are truncated to Excel's 31-char limit and deduplicated.
- **PDF door notices** — one per hall.
- **PDF invigilator report** — grid + roll lists.

Blank subject codes render as "All Papers".

---

## Compatibility and defect fixes

- Multi-round seating: seats held by other subject rounds are pre-occupied; a hall-subset
  arrange no longer wipes unrelated rounds.
- Engine hardening: mixed bench types in one hall are rejected (no invalid slot 2 on a
  single-seater), bench grids are read without mutating shared entities, a global 10-second
  wall-clock solve budget replaces unbounded searches, and `solved` is reported accurately.
- Excel import: header trailing/leading punctuation is trimmed (`Roll No.` works), bench
  types are parsed strictly, numeric roll numbers reject fractions/imprecise 64-bit values,
  all fields are length-validated before insert, and DB length errors are no longer
  misreported as duplicates.
- Excel/PDF export: null department / hall name no longer crash, one shared style is reused
  for vacant cells (no 64,000-style explosion), and the palette index uses `floorMod` so
  `Integer.MIN_VALUE` hashes are safe.
- Configuration: no committed DB password, SQL logging off by default, demo accounts
  opt-in, destructive mock endpoints require `ADMIN`/`EXAM_CELL` in enforced mode.

Institutional accounts, tenant isolation, subscriptions, quotas and file storage are
intentionally **out of scope** for this phase; see
[the assessment](docs/ASSESSMENT-2026-11-03.md) for the commercial-readiness roadmap.
