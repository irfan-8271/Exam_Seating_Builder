# Examination Seating Arrangement Builder

A Spring Boot application that generates anti-cheating exam seating plans. Students are
separated by a **configurable conflict key** (which attributes make two students
"classmates"), with **difficulty levels** controlling how far apart classmates must sit,
across an M×N grid of SINGLE_SEATER or DUAL_SEATER benches per hall.

**Stack:** Spring Boot 3.5 · Java 17 · MySQL 8 · Hibernate (JPA) · Apache POI (Excel) · OpenPDF (PDF)

---

## Quick start

```bash
# 1. MySQL must be running. Configure credentials via environment (nothing secret in the repo):
export DB_USERNAME=root
export DB_PASSWORD=yourpassword
# optional: export DB_URL='jdbc:mysql://localhost:3306/examseating?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true'

# 2. Run
mvn spring-boot:run

# 3. Open http://localhost:8080
```

Optionally `export SEED_DEMO=true` to seed demo rooms/students and a first seating round.

## Tests

```bash
mvn test
```

The engine tests cover gap semantics, difficulty-level density bounds, configurable
grouping keys, case-insensitive grouping, and isolation-flag parity between the two
solver paths. Import tests cover per-row transaction isolation.

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
round (`POST /api/seating/arrange` with `subjectCode`), scope exports
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

Demo users (BCrypt-hashed, seeded at startup) are documented in `DataSeeder`.

## Outputs

- **Excel workbook** — master list, per-hall bench maps (department-colored), legend.
  Sheet names are truncated to Excel's 31-char limit and deduplicated.
- **PDF door notices** — one per hall.
- **PDF invigilator report** — grid + roll lists.

Blank subject codes render as "All Papers".
