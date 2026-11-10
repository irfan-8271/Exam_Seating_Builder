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
# optional: export SECURITY_MODE=enforced (bearer token / HTTP Basic + role checks)
# optional: export STORAGE_DIR=./data/storage   (uploaded files)
# optional: export MAX_UPLOAD_SIZE=25MB        (global servlet ceiling; per-plan limits are lower)
# optional: export TOKEN_TTL_HOURS=24           (bearer token lifetime)
# optional: export DEFAULT_INSTITUTION_SLUG=default

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

The test suite runs on an **in-memory H2 database** (`MODE=MySQL`), so it needs no
MySQL server — `mvn test` works straight after cloning. Production still runs on
MySQL 8; only `src/test/resources/application.yml` and the test-scoped H2
dependency in `pom.xml` differ from the production setup.

The engine tests cover gap semantics, difficulty-level density bounds, configurable
grouping keys, case-insensitive grouping, and isolation-flag parity between the two
solver paths. Import tests cover per-row transaction isolation. The placement
validator (see below) is covered by its own test class, and the seat-move endpoint
by an integration test.

---

## REST API (v1)

The primary API is resource-oriented under `/api/v1`. The machine-readable
[OpenAPI 3 contract](src/main/resources/static/openapi.yaml) is also served at
`/openapi.yaml` when the app is running.

| Resource | Endpoints |
|---|---|
| Students | `GET/POST /api/v1/students`, `GET/PUT/PATCH/DELETE /api/v1/students/{id}`, `POST /api/v1/students/imports` |
| Student identification | `GET/PUT/DELETE /api/v1/students/identity`, `POST /api/v1/students/imports/preview` |
| Rooms | `GET/POST /api/v1/rooms`, `GET/PUT/PATCH/DELETE /api/v1/rooms/{id}`, `GET /api/v1/rooms/{id}/benches`, `POST /api/v1/rooms/imports` |
| Seating plans | `GET/POST/DELETE /api/v1/seating-plans`, `GET /api/v1/seating-plans/allocations`, `PATCH /api/v1/seating-plans/allocations/{allocationId}` |
| Seating rounds | `GET /api/v1/seating-rounds`, `GET /api/v1/seating-rounds/current` |
| Exports | `GET /api/v1/exports`, `GET /api/v1/exports/excel`, `GET /api/v1/exports/pdf/door-notices`, `GET /api/v1/exports/pdf/invigilator` |
| Accounts | `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `GET /api/v1/auth/me`, `POST /api/v1/auth/logout`, `GET/PATCH /api/v1/institutions/current` |
| Plans & usage | `GET /api/v1/plans`, `GET/PUT /api/v1/subscriptions/current`, `GET /api/v1/usage`, `GET /api/v1/generations` |
| Files | `GET/POST /api/v1/files`, `GET/DELETE /api/v1/files/{id}` |

Conventions:

- Collection `GET`s return a `PageResponse` envelope (`content`, `page`, `size`,
  `totalElements`, `totalPages`, `first`, `last`) and accept `page` (0-based),
  `size` (1–200), resource-specific filters, `sortBy` and `direction`.
- `POST` returns **201 Created** with a `Location` header; `DELETE` returns **204**.
- `PATCH` accepts only whitelisted fields; unknown/immutable fields are a 400.
- Every failure returns the same `ErrorResponse` envelope: `timestamp`, `status`,
  `code`, `error`, `message`, `path`, `details`. Typical codes: `VALIDATION_FAILED`,
  `MALFORMED_JSON`, `INVALID_PARAMETER`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`,
  `UNSUPPORTED_MEDIA_TYPE`, `CONFLICT`, `SEATING_ERROR`, `PAYLOAD_TOO_LARGE`,
  `UNAUTHORIZED` (401), `QUOTA_EXCEEDED` (429).
- `POST /api/v1/seating-plans` with `clearExisting=false` **requires** `subjectCode`.
  Seats already used by other papers are passed to the engine as pre-occupied,
  so two rounds never collide on the same `(bench, slot)`.

**Legacy aliases:** all original `/api/students`, `/api/rooms`, `/api/seating/*`,
`/api/export/*`, `/api/mock/*` and `/api/auth/*` paths remain available and keep their
original response shapes so the bundled SPA continues to work. They are marked
`@Deprecated` in code and `deprecated: true` in the OpenAPI contract; new integrations
should use `/api/v1`.

---
---

## Institutional accounts, tenant isolation and plans

The backend now supports multiple institutions on one database. Every student,
hall and seating allocation carries an `institution_id`; records written before
this phase have `NULL` and are treated as belonging to the seeded **default**
institution, so an existing database keeps working without a migration step.

`TenantFilter` resolves the tenant on every request:

1. a valid `Authorization: Bearer <token>` from `/api/v1/auth/login` (also publishes
   the user's Spring Security authorities so `@PreAuthorize` works for token clients);
2. in `SECURITY_MODE=open` only, an `X-Institution-Id` header for local demos;
3. otherwise the default institution.

In `SECURITY_MODE=enforced` the header is ignored, so a caller cannot switch tenant
by hand. Reads are filtered by `TenantSupport.visible(...)`; single-record reads and
mutations call `require(...)`, which returns **404** rather than 403 to avoid leaking
whether another institution's row exists. Bulk demo resets only delete the current
tenant's rows.

`AccountSeeder` installs the default institution and four plans on first boot:

| Plan | Students | Halls | Generations / month | Storage | Max file | Seats / generation |
|---|---|---|---|---|---|---|
| FREE | 50 | 3 | 5 | 10 MB | 2 MB | 100 |
| STARTER | 500 | 20 | 50 | 100 MB | 5 MB | 500 |
| PRO | 5,000 | 100 | 500 | 1 GB | 10 MB | 5,000 |
| ENTERPRISE | 100,000 | 1,000 | 10,000 | 10 GB | 25 MB | 50,000 |

`QuotaService` enforces student/hall caps on create and Excel import, the monthly
generation count and per-generation seat cap before a solve, and storage/file-size
caps on upload. Every breach is a **429** `QUOTA_EXCEEDED` response with the quota
name in `details`. Every successful seating round is recorded in `generation_usage`.
`PUT /api/v1/subscriptions/current` upgrades or downgrades; a downgrade that would
immediately exceed the new limits is rejected.

Uploaded files are written under `STORAGE_DIR/<institutionId>/` with a random
on-disk name (client filenames are stripped of paths and control characters). The
metadata row is tenant-scoped and counted against the storage quota.

Registration creates the institution, an `ADMIN` user (BCrypt password), a FREE
subscription and a revocable opaque bearer token. Tokens live in `auth_tokens` with
a configurable TTL; log out revokes one. Public endpoints: register, login and the
plan catalogue. Institution updates and plan changes require `ADMIN`.

> Legacy `/api/...` aliases remain functional for the bundled SPA and are also
> tenant-scoped. They are deprecated and will be removed once the React frontend
> replaces them.

---

## Adjusting a plan by hand

Every round now stores the parameters it was solved with (the gap values, the
conflict scope, the grouping key and the bench-pairing rule) in a
`seating_rounds` row — one per institution and subject scope. Nothing else
changes: the table is created automatically by Hibernate (`ddl-auto: update`),
and a round that is re-arranged simply overwrites its own row.

That makes it possible to change a single seat of a finished plan instead of
re-arranging every paper:

```http
PATCH /api/v1/seating-plans/allocations/{allocationId}
Content-Type: application/json

{ "roomId": 7, "benchRow": 3, "benchCol": 5, "slot": 1 }
```

The seat is addressed by its position in the hall grid — `roomId` picks the
hall, `benchRow`/`benchCol` the bench inside it and `slot` the seat on that
bench (1 on a single-seater, 1 or 2 on a dual-seater). The response is the
allocation as it now stands.

The new seat is checked against **the same rules the plan was built with**, so a
hand-made change cannot break the anti-cheating invariants:

- the seat must be free (the seat the student comes from becomes free);
- the bench-mate rule applies to the other seat of a dual-seater bench;
- the horizontal, vertical and diagonal gaps apply to the students already
  seated in that hall;
- the optional horizontal department isolation applies as well.

A move that breaks one of these rules is refused with **409 CONFLICT** and
nothing is changed — the message names the student that blocks the placement.
An unknown allocation or hall is a **404**; a seat that does not exist on that
bench is a **400**.

Students of other rounds seated in the same hall are taken into account: the
separation rules are about who sits next to whom, not about which paper they
are writing.

> A hall that was never part of a round gets its bench grid created on the
> first move into it, exactly as it does when it is arranged.

The rules a round was solved with are readable over HTTP, so a client can show
them or check them:

```http
GET /api/v1/seating-rounds/current[?subjectCode=MATH101]
```

```json
{
  "subjectKey": "MATH101", "subjectCode": "MATH101",
  "horizontalGap": 1, "verticalGap": 1, "diagonalGap": 1,
  "conflictScope": "DEPARTMENT_AND_YEAR",
  "benchPairingRule": "DIFFERENT_DEPARTMENT_AND_YEAR",
  "horizontalDeptIsolation": true,
  "groupAttributes": ["department", "yearsession"], "groupMatchMode": "ALL",
  "seatedCount": 120, "createdAt": "2026-11-09T12:00:00Z"
}
```

`GET /api/v1/seating-rounds` lists every round of the institution.

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

Comparison is case-insensitive and whitespace-tolerant.

### How a student is identified

An institution identifies its students in its own way: a roll number, a registration
number, an admission number, a batch plus a serial, a registration number plus a
department, an alphanumeric code — or a sheet with no identifier column at all. The
system therefore never asks "what is the roll number?", it asks "what is the value of
*this* attribute?".

`rollNo` is now **optional**. What identifies a student is decided in this order:

1. **Per import** — `POST /api/v1/students/imports` takes an optional
   `identityAttributes` form parameter: one attribute (`registrationnumber`) or a
   combination of several (`rollno,batchnumber`). The name may also be the header
   exactly as it appears in the file.
2. **Per institution** — `PUT /api/v1/students/identity` stores the rule for every
   later upload and every student added by hand. `DELETE` returns to auto-detection.
3. **Auto-detected** — nothing configured: the importer looks for a column that is
   known to carry an identifier, else a column that is unique across the file, else a
   combination of columns that is unique together.

`POST /api/v1/students/imports/preview` analyses a roster without saving anything and
answers the question the institution actually has — *which column identifies a
student?*:

```http
POST /api/v1/students/imports/preview
Content-Type: multipart/form-data

file=@roster.xlsx
```

```json
{
  "totalRows": 120,
  "columns": [
    { "key": "name",         "title": "Student Name",    "kind": "typed",     "complete": true,  "unique": true,  "distinctValues": 120 },
    { "key": "applicationid","title": "Application Id",  "kind": "attribute", "complete": true,  "unique": true,  "distinctValues": 120 },
    { "key": "department",   "title": "Department",       "kind": "typed",     "complete": true,  "unique": false, "distinctValues": 4 }
  ],
  "suggestedIdentities": [
    { "attributes": ["applicationid"], "label": "Application Id", "unique": true },
    { "attributes": ["department", "applicationid"], "label": "Department + Application Id", "unique": true }
  ],
  "currentScheme": { "id": null, "label": "not configured", "attributes": [], "displayAttribute": null }
}
```

The chosen attributes are folded into a canonical **identity key** that is unique per
institution — that is what stops the same student being imported twice under a
different column layout, and it is what the plan and the exports show next to the
student's name. The existing output format is unchanged: the roll-number column of the
Excel export and the PDF lists still carries the roll number where there is one, and
the institution's own identifier where there is not.

Every column the system does not know is **kept**, not thrown away: it becomes a named
attribute of the student (`applicationid`, `batch`, `hostel`, …) that the API returns
and that the seating engine can group by.

## Subject codes are optional

`subjectCode` is optional metadata, never a seating factor. When present it can filter a
round (`POST /api/v1/seating-plans` with `subjectCode`), scope exports
(`?subjectCode=...`) and preserve other papers' plans (`clearExisting=false` requires a
`subjectCode` filter).

## Input files

Student rosters are matched by header name, and the layout is free: **no column is
required**, not even a roll number. Headers are matched by alias ("Roll No.", "Reg
No", "Registration Number", "Sem", "Batch", ...); a column the system does not know is
kept as a named attribute.

```
STUDENTS.xlsx : any columns — Name | Roll No | Department | ... (none is required)
ROOMS.xlsx    : Hall Name | Rows | Columns | Bench Type   (SINGLE_SEATER | DUAL_SEATER)
```

Each row imports in its own transaction: a bad row (e.g. an empty identifier, a
duplicate identity, a value Excel cannot store exactly) is reported in the result and
never rolls back the good rows. The result also carries a `notes` list saying how the
students in the file were identified:

```json
{ "totalRows": 120, "imported": 119, "errors": ["Row 7: this row cannot be identified — 'Application Id' is empty"],
  "notes": ["Students are identified by 'Application Id' (detected automatically)."] }
```

A file whose identifying column does not actually identify (two rows share the same
value) is refused as a whole rather than merged into one student — the fix is to choose
another attribute, which the preview shows.

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

### Fixed in this round

- `POST /api/v1/auth/logout` returned **500** and left the token usable — the
  service method was not transactional, so the revocation was rolled back by the
  read-only transaction. It now returns **204** and the token stops working.
- The legacy `GET /api/auth/me` returned **500** in `open` mode (a null
  `Authentication`). It now returns **403** with the usual error envelope.
- The demo data seeded 60 students into a plan that allows 50, so the first
  student added afterwards was rejected with a quota error. It seeds 40 now.
- `GET /api/v1/seating-plans?page=&size=` dropped the pagination metadata. It
  reports `page`, `size`, `totalElements` and `totalPages` again.
- A malformed request with a wrong enum value answered *"check the enum
  spellings"* without naming the accepted values. The message now lists them
  (`BASIC`, `INTERMEDIATE`, `ADVANCED`, …).
- The `seating_rounds` table was written but never read — the rules a plan was
  built with were not reachable. `GET /api/v1/seating-rounds` and
  `GET /api/v1/seating-rounds/current` expose them.
- The OpenAPI contract documented 36 paths while the application served 40
  operations, and several legacy aliases were missing from it. The contract and
  the implementation now agree operation by operation (67 operations).

### Upgrading an existing database

The student table changed: `roll_no` may now be empty, a new `identity_key` column
carries the canonical identity, and the `student_attributes` table holds the
institution's own columns. Hibernate creates the new column and the new table on the
next start (`ddl-auto: update`).

One thing has to be done by hand, because Hibernate cannot drop an index:

```sql
ALTER TABLE students DROP INDEX uk_students_roll_no;
```

The old index made the roll number unique across *all* institutions, which stops an
institution from using a registration number that another institution already uses.
The replacement index `uk_students_identity` covers `(institution_id, identity_key)`.

### Earlier

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
- Test suite: the sample workbooks are opened read-only, so `mvn test` no longer
  rewrites `sample_excel_files/students_combined_300.xlsx` and leaves the working
  tree dirty. The suite runs on an in-memory H2 database and needs no MySQL server.

Institutional accounts, tenant isolation, subscriptions, quotas and file storage are
now implemented in the backend; see **Institutional accounts, tenant isolation and
plans** above. Billing-provider integration, invoicing and payment webhooks are the
remaining commercial pieces. The original 92-scenario assessment lives in commit
`47575b2` of the repository history.
