# Assessment: Examination Seating Arrangement Builder

**Date:** 2026-11-03
**Revision under test:** `b64d9e62196bf2476ced13f42bd0302a90d2a065` (`arena/5fa4e0a3-exam-seating-builder`)
**Scope:** evaluation only. No authentication, user management, subscription, quota, storage or other product features were implemented. This document is the only change.

---

## 1. Executive verdict

**The domain engine is good; the product foundation is not yet commercial.**

The seating solver is the strongest part of the project: it is deterministic, configurable, and its
outputs satisfied an independently written constraint checker in every valid scenario tested —
including mixed halls, 10 halls / 300 students, custom grouping keys, `ANY`/`ALL` match modes,
case- and whitespace-insensitive grouping, Unicode/blank/null metadata, spare capacity, all four
conflict scopes, both bench-pairing rules, gaps 0–10, and unsatisfiable-input rejection. That core
is genuinely reusable.

The surrounding application is a **single-tenant, single-institution MVP**. Every table, repository
query, unique constraint, delete operation, export and endpoint is global. There is no institution
concept, no file storage (uploads are consumed and discarded), no usage/generation accounting, no
plan versioning, no tenant-scoped quota, and no separation between "one school's data" and
"another school's data". Default security is *off*, a real-looking database password is committed
as a fallback, and destructive global endpoints are reachable without a role check.

**Compatibility with the proposed subscription product: not ready as-is.** The engine can be kept
as a stateless bounded context, but the persistence model, API layer, and security model need a
deliberate multi-tenant redesign before accounts, plans, storage limits, generation limits or
per-plan student caps can be added. This is a structural refactor, not an incremental feature.

**Test outcome:** 92 executed scenarios — 52 PASS, 8 FAIL (reproduced defects), 32 INFO
(stress/measurements/observations), 0 harness errors. Eight reproduced failures, plus further
robustness, consistency and scalability findings, are documented in §4–§5; the most consequential
are the multi-subject seat collision, the Excel export crash on a permitted null department, the
documented-but-rejected `"Roll No."` header, and invalid seats on mixed bench types.

---

## 2. What was tested and how

### 2.1 Constraints of this environment

`mvn test` could **not** be run here: the sandbox has no JVM or Maven, Maven Central is not
reachable, and the repository's own `ComprehensiveCombinationIntegrationTest` is a
`@SpringBootTest` that requires a live MySQL server (it also wipes the database in `@BeforeEach`).
There is no test profile, no H2/testcontainer configuration, no Maven wrapper and no CI workflow,
so the shipped suite is not runnable in a clean environment. That is itself a readiness finding
(§6.6).

### 2.2 Harness used instead

To get *dynamic* results rather than a purely static review, I compiled the **real, unmodified**
production sources with Eclipse ECJ 3.38 (Java 17 target) on a JDK 17 runtime:

- `engine/SeatingArrangementEngine.java` (2,060 lines, unchanged)
- `service/SeatingService.java`, `ExcelImportService.java`, `ExcelExportService.java`,
  `PdfExportService.java`
- `dto/ArrangeRequest.java`, `ImportResult.java`, `SeatingResponse.java`,
  `exception/ResourceNotFoundException.java`

They were linked against minimal in-memory stand-ins for JPA entities, Spring repositories /
annotations, Apache POI and OpenPDF. Services received an injected constructor in place of
Lombok's `@RequiredArgsConstructor`; **no production logic was altered**. An independent verifier
re-checked every generated plan against the documented rules (unique seat, valid slot, unique
student, horizontal/vertical/diagonal gaps, horizontal department isolation, bench-mate pairing,
conflict key, seated + unplaced = roster size).

The harness lived outside the repository (`/tmp/harness`) and is **not committed**.

### 2.3 Caveats

- Timings are in-memory harness timings on this sandbox — they show algorithmic shape, not
  production latency.
- POI/OpenPDF behaviour was emulated, so conclusions about DB unique constraints are derived from
  the JPA `@UniqueConstraint` declarations and about library limits from the libraries'
  documented/source behaviour (e.g. Excel's 64,000 cell-style limit; OpenPDF 1.3.30
  `Phrase(String, Font)` tolerates null).
- The defects in §4 were reproduced by executing the actual Java source paths; the harness only
  replaced the surrounding runtime.
- Not covered by execution: real MySQL, HTTP filter chain, browser UI, network-level behaviour,
  and concurrent DB transactions. Those are assessed statically in §6.

---

## 3. Results at a glance

| Group | Cases | PASS | FAIL | INFO | Headline |
|---|---|---|---|---|---|
| Engine (`E*`) — baselines, presets, custom gaps, scopes, grouping keys, pairing rules, null/Unicode/duplicate metadata, grid anomalies, determinism, concurrency | 46 | 40 | 1 | 5 | All valid plans constraint-clean except `E29`; unsatisfiable inputs rejected correctly |
| Scaling / stress (`S_*`) — 25→400 students, fast vs general path, dense instances, int overflow, impossible-instance latency | 13 | 0 | 0 | 13 | 93 ms at 400 students; 6–10 s on provably impossible instances |
| Service workflow (`W*`) — arrange/view/clear, subject rounds, room subsets, metrics | 8 | 3 | 1 | 4 | 1 critical seat-collision defect, several metric inconsistencies |
| Excel import (`I*`) — aliases, numeric/formula cells, bad rows, duplicates, bounds, 5,000-row bulk | 17 | 7 | 3 | 7 | 3 defects plus validation gaps |
| Excel export (`X*`) — null metadata, empty plan, sheet names, style counts | 5 | 1 | 1 | 3 | 1 crash + 1 Excel style-limit breach |
| PDF export (`P*`) — null metadata, pathological department, null hall name | 3 | 1 | 2 | 0 | 2 crashes |
| **Total** | **92** | **52** | **8** | **32** | **0 harness errors** |

`PASS` means the observed behaviour matched the expectation for that case; a case can PASS
while still exposing a weakness (e.g. `E37` asserts that an unvalidated null list produces an
NPE). Those are discussed as findings, not counted as silent successes.

A full case-by-case catalogue is in Appendix A.

---

## 4. Reproduced defects

Severity: **C** critical (blocks a core workflow), **H** high (user-visible failure or data
corruption), **M** medium (robustness/scale/quality), **L** low (cosmetic or unlikely).

### F-01 — C — Incremental multi-subject rounds reuse occupied seats
**Evidence:** `W02_two_subject_rounds_collide`. Arranging `PAPER-A` then `PAPER-B` with
`clearExisting=false` reused **8 of the same seats**.
**Cause:** `SeatingService.arrange` solves from an empty model
(`SeatingService.java:115-119`) and only then deletes the target subject's allocations
(`:126-133`). The engine has no knowledge of seats already occupied by another paper. In the real
schema, `uk_seating_allocations_bench_slot` (`SeatingAllocation.java:37-38`) makes the second
round fail with a constraint violation → HTTP 409 and a rolled-back transaction.
**Impact:** the documented "preserve other papers' plans" workflow is unusable for a normal exam
cycle with multiple papers. **Direction:** treat existing allocations as occupied input to the
solver, or scope each round to a disjoint set of halls/timeslots; add a plan/round identifier.

### F-02 — H — Excel export crashes on a permitted null department
**Evidence:** `X01_null_department` → `NullPointerException`.
**Cause:** `ExcelExportService.styleForDept` calls `d.hashCode()` on the department key
(`:205-207`); `dept.get(key)` is null whenever a student has no department — which the entity,
the REST API and the Excel importer all explicitly allow (`Student.java:65-67`, README "Only Name
and Roll No are required").
**Impact:** `GET /api/export/excel` returns 500 for the exact school-style rosters the project
advertises support for. PDF export tolerates the same input (`P01`), so the two exporters are
inconsistent. **Direction:** normalise null to a sentinel and use `Math.floorMod` on a stable key.

### F-03 — H — README-documented `"Roll No."` header is rejected
**Evidence:** `I02b_trailing_dot_header_rejected`, `I02c_leading_dot_header_rejected`.
**Cause:** `normalizeHeader` (`ExcelImportService.java:260-263`) trims *before* replacing
`.`/`_`/`-`, and never trims afterwards. `"Roll No."` becomes `"roll no "` (trailing space),
which matches no alias; `".Name"` becomes `" name"`.
**Impact:** the most common real-world header spelling fails with a 400, despite README claiming
alias support. Thousands of ordinary school spreadsheets use `Roll No.`, `Reg No.`, etc.
**Direction:** trim after normalisation; add regression tests for trailing/leading punctuation.

### F-04 — H — Mixed bench types in one hall produce physically impossible seats
**Evidence:** `E29_mixed_bench_types_in_room` → `slot 2` allocated on a `SINGLE_SEATER` bench.
**Cause:** the layer-decomposed fast path decides seat counts from `room.getBenchType()`
(`SeatingArrangementEngine.java:653-663`) while the general path uses each `Bench.getBenchType()`
(`:1115-1123`). Capacity checks also use the room-level type (`:1820-1828`).
**Impact:** a room whose bench grid is inconsistent (dirty data, manual DB edit, future
per-bench layout feature) silently yields a student at a seat that does not exist.
**Direction:** enforce one bench type per room at the model boundary, or drive capacity and slot
generation consistently from the bench collection.

### F-05 — M — Excel export creates one cell style per empty cell
**Evidence:** `X03` = 10,002 styles for a 100×100 hall; `X05` = **90,002 styles** for 300×300,
exceeding Excel's 64,000-style ceiling by 26,002.
**Cause:** `emptyStyle(workbook)` is called per empty cell (`ExcelExportService.java:170`,
`:222-231`) and each call does `workbook.createCellStyle()`.
**Impact:** a legally configured hall (`@Max(500)`) can produce a workbook Excel refuses to open,
plus large memory spikes. **Direction:** create and reuse one shared "empty" style.

### F-06 — M — PDF export crashes for a department whose hash is `Integer.MIN_VALUE`
**Evidence:** `P02_hash_overflow_department` → `ArrayIndexOutOfBoundsException`.
**Cause:** `Math.abs(deptName.hashCode())` (`PdfExportService.java:621`) stays negative for
`Integer.MIN_VALUE`; `% PALETTE.length` then yields a negative index. Department is free-form,
unvalidated text, so an attacker or an odd but legitimate label can trigger it.
**Direction:** use `Math.floorMod(hash, PALETTE.length)`.

### F-07 — L — PDF export NPE on a null hall name
**Evidence:** `P03_null_hall_name_pdf`. `buildHallBanner` calls
`room.getHallName().toUpperCase()` (`PdfExportService.java:312`). REST creation validates
`@NotBlank`, so this needs dirty DB data or a future bypass; still an unguarded 500.
**Direction:** null-safe label.

### F-08 — M — Imports bypass bean validation and misreport the resulting DB errors
**Evidence:** `I07_overlong_fields_not_validated` (500-char name / 200-char roll),
`I15_room_import_over_500_not_blocked` (600 rows imported, 1,200 benches built although REST
enforces `@Max(500)`).
**Cause:** `validateStudent` (`ExcelImportService.java:180-186`) only checks blanks; the
`@Size`/`@Max` constraints on `Student`/`ExamRoom` apply to `@Valid @RequestBody`, not to
imports. `friendlyMessage` (`:213-220`) maps *any* `DataIntegrityViolationException` to
"duplicate value for a unique field", so a length-overflow error is reported as a duplicate.
**Impact:** confusing row errors, unbounded bench-grid construction, and a memory/DoS path via
large `Rows`/`Columns` values. `getTotalBenches()`/`getCapacity()` also overflow `int`
(`S_overflow_capacity_math`: 50,000×50,000 → `-1,794,967,296` benches).
**Direction:** share one validation path between REST and import; cap dimensions with `long`
arithmetic; classify DB errors precisely.

### F-09 — M — Numeric roll numbers silently lose precision
**Evidence:** `I05_numeric_roll_and_decimal`: `9007199254740993` imported as
`9007199254740992`; `1002.5` accepted as a roll number.
**Cause:** `cell()` casts an integral `double` to `long` (`ExcelImportService.java:279-296`).
**Impact:** realistic long registration numbers (Aadhaar-like, national IDs) are corrupted, and
since roll number is the identity key, duplicate/collision errors follow.
**Direction:** treat identifiers as text; never round-trip them through floating point.

### F-10 — M — Malformed input surfaces as 500 instead of 400
**Evidence:** `E37` (null rooms list), `E38` (null student element), `E39` (null element in
`groupAttributes` → `List.copyOf` NPE).
**Cause:** no null-defensive validation at the engine/config boundary
(`SeatingArrangementEngine.java:165-168`, `:301-306`).
**Impact:** a malformed JSON body produces an internal server error with a generic message rather
than an actionable 400; also a cheap unauthenticated error-amplification vector in open mode.

### F-11 — M — Provably impossible requests burn ~6–10 s of CPU inside a DB transaction
**Evidence:** `E19` 9,892 ms, `E21` 6,654 ms, `S_unsat_latency` 8,473 ms for a 12-student
instance that a pre-check could reject in microseconds.
**Cause:** four deterministic search profiles (`:194-200`) each run until the 500,000-step
budget when the necessary-condition pre-checks are not tight enough. `SeatingService.arrange` is
`@Transactional`, so the DB transaction stays open for the whole search.
**Impact:** in open mode an anonymous caller can pin a CPU core and a DB connection for ~10 s;
with several institutions this becomes a denial-of-service and pool-exhaustion risk.
**Direction:** tighten pre-checks, add a wall-clock limit, run solves outside the write
transaction / as queued jobs, and add rate limiting.

### F-12 — M — Engine mutates its inputs; concurrent use is unsafe
**Evidence:** `E42` (shared rooms), `E43_concurrent_stress` — one of 320 concurrent
`arrange()` calls on shared `ExamRoom` objects threw `ConcurrentModificationException`; a repeat
run was clean, confirming a nondeterministic race.
**Cause:** `ensureBenchGrid` sorts and rebuilds `room.getBenches()` in place
(`SeatingArrangementEngine.java:2008-2035`), although the class is documented as stateless.
**Impact:** unsafe for shared/cached entity objects, memoisation or parallel solving; in JPA the
room is a managed entity, so in-place mutation can also trigger unexpected flushes.
**Direction:** copy the grid into internal value objects; never mutate caller-owned entities.

### F-13 — L — `view()` metrics are inconsistent with what was arranged
**Evidence:** `W05` (arrange used 1 of 2 halls, `totalSlots` still reports both halls'
capacity), `W07` (`view(null)` reports `unplaced=6` because it counts all 12 students across two
papers against 6 saved seats).
**Cause:** `SeatingService.view` sums capacity of *all* rooms and computes
`unplaced = studentCount - allocations` (`:161-173`), with no concept of a round or selected
halls. `solved` is hard-coded `true`.
**Direction:** persist round metadata (rooms, student set, timestamp) and derive metrics from it.

### F-14 — L — Loose bench-type parsing
**Evidence:** `I08`: `"dual seater (2 students)"` and `"DUAL-SINGLE"` both accepted as
`DUAL_SEATER` because `parseBenchType` uses `contains("DUAL")` / `contains("SINGLE")`
(`ExcelImportService.java:201-210`). Ambiguous values are silently misclassified.

### F-15 — L — An unfiltered arrange destroys all per-paper plans
**Evidence:** `W04` (two rounds → one unfiltered arrange replaces them with a single mixed
roster plan), `W08` (arranging only hall B wipes hall A's allocations).
**Cause:** `clearExisting=true` always calls `deleteAllInBatch()`
(`SeatingService.java:126-130`); it is not scoped to `roomIds` or subject. Also
`clearExisting=false` without a subject is rejected *after* the solver has already run
(`W03`, `:126-129`) — wasted work.
**Direction:** scope deletion to the exact round being replaced; validate before solving.

### F-16 — L — `totalSlots` reports seats that are not usable
**Evidence:** `E45` (12 students in a 24-seat dual hall solved with only 12 physical seats
usable), `E46` (13 rejected although `validateCapacity` sees 24 seats).
**Cause:** the dual-bench rule requires two different departments per bench
(`:1962-2006`), but capacity is computed from physical seats (`:1820-1828`).
**Impact:** capacity dashboards and quota logic built on `totalSlots` would overstate real
capacity by up to 2×.

### F-17 — L — Hard-coded, colliding department colours
**Evidence:** `S_many_departments` (40 departments seated fine, but Excel has 12 palette entries
and PDF 7), `PdfExportService.java:88-98` maps English engineering tokens (`cse`, `elec`,
`mech`…) and falls back to a hash. School labels such as "XII-A" get arbitrary colours and
collide; legends become ambiguous.

---

## 5. Other observed weaknesses (non-crashing)

- **Global identity keys.** `uk_students_roll_no`, `uk_exam_rooms_hall_name`,
  `uk_users_username` and `uk_seating_allocations_bench_slot` are all global
  (`Student.java:38`, `ExamRoom.java:37`, `User.java:31`, `SeatingAllocation.java:37`). `I12`
  shows a second institution reusing roll `1001` is rejected as a duplicate. Two colleges cannot
  coexist.
- **No plan history.** `deleteAllInBatch()` and `deleteBySubjectCode()` destroy prior plans.
  There is no generation record, no audit trail, no "who generated what when" — the exact data a
  billing system needs.
- **Hard-wired pairing attributes.** `horizontalDeptIsolation` and both `BenchPairingRule`
  variants compare `department`/`yearSession` specifically, not the configurable `groupAttributes`
  key. `E45` demonstrates an institution that groups by `COURSE` still cannot pair bench-mates by
  course. "Nothing about classification is hardcoded" (README) is true for the *conflict key*
  only, not for the bench rule.
- **Limited institutional vocabulary.** Three free-form attributes (`department`, `course`,
  `yearSession`) with 60/60/30-char limits; no custom fields, no separate section/stream/subject,
  no exam date/timeslot/session entity, no per-bench layouts, no accessibility constraints (extra
  time, scribe, wheelchair), no invigilator assignment. `subjectCode` is one 20-char string.
- **`ANY` mode treats missing values as shared.** `canon(null)` returns `""`, so every student
  with no department conflicts with every other such student in `ANY` mode (`E44`); a school that
  omits Department gets much stricter layouts than expected.
- **Header mapping is a fixed English table.** `HEADER_ALIASES` (`ExcelImportService.java:70-85`)
  cannot be extended per institution; README itself defers a "mapping UI" to the future.
  Case/punctuation handling is fragile (F-03).
- **Formula cells depend on cached results.** `I06`: imports read the cached value, not the
  formula. Files produced by libraries that don't compute formulas yield blank names/rolls.
- **Inconsistent exporters.** PDF handles null metadata (`P01`); Excel crashes (`X01`). PDF
  invigilator sheets show `Subject: null` when the code is absent; Excel uses "All Papers".
- **Frontend limitations.** `GET /api/students` loads the entire roster into one DOM table with
  no pagination/virtualisation; the colour palette is hard-coded to five engineering departments
  (`app.js:7-24`); a "Reset data" button calls the global destructive endpoint.

---

## 6. Commercial viability assessment

### 6.1 Requirement-by-requirement compatibility

| Planned capability | Current support | Verdict |
|---|---|---|
| 1. Institutional accounts | `User` has `username`/`password`/`role` but **no institution relation**; `users.username` is globally unique; no signup, org, membership or tenant entity | **Not compatible** — needs a tenant model and every table/query scoped by `institution_id` |
| 2. Paid plans + free generation allocations | No plan, subscription, billing, entitlement, usage counter or free-tier state exists. Nothing records that a generation happened | **Not compatible** — needs plan/subscription/usage entities plus an enforcement interceptor on `arrange`/import/export |
| 3. Upload & store files within storage limits | Uploads are `InputStream`s consumed by POI and **discarded**; no file entity, table, object store, path, checksum, retention or quota. Only a 10 MB multipart cap | **Not compatible** — needs a storage layer, metadata table and per-tenant quota accounting |
| 4. Generation limit | No generation entity, counter, history or idempotency key; `SeatingService.arrange` is an anonymous mutation that deletes the previous plan | **Not compatible** — needs immutable generation records and a quota check before solving |
| 5. Predefined student limit per subscription | `students` is a single global table; `studentRepository.count()` is global; no per-tenant count or limit; global unique roll numbers collide across institutions | **Not compatible** — needs tenant-scoped uniqueness and a quota check on create/import |
| 6. Upgrade / downgrade plans | No plan state machine, no billing/proration, no entitlement propagation, no mid-cycle quota re-evaluation hooks | **Not compatible** — needs a plan/entitlement module |

**Overall:** 0 of 6 capabilities are supported today. All six depend on the same missing primitive:
**tenant identity + usage accounting**. None of them can be bolted on without touching the entity
model and every repository method.

### 6.2 The central architectural blocker: single-tenancy by construction

Every repository method is global (`findAll()`, `findBySubjectCode()`, `findAllWithDetails()`,
`count()`), every entity lacks an owner, and there are four global unique constraints. Adding
`institution_id` means:

- changing all six entities and their unique constraints to composite keys
  (`(institution_id, roll_no)`, `(institution_id, hall_name)`, `(institution_id, username)`);
- rewriting every derived and `@Query` repository method to filter by tenant;
- threading tenant context through every service and controller (currently the only "who" is an
  optional HTTP Basic user with a global role);
- replacing `deleteAllInBatch()` / `deleteBySubjectCode()` with tenant-scoped deletes;
- making exports and `view()` tenant-aware.

`spring.jpa.hibernate.ddl-auto: update` (`application.yml:37`) with no Flyway/Liquibase
migrations makes that schema migration risky. **Recommendation:** design the tenant boundary
first, then migrate; retrofitting it after institutions have data will be far more expensive.

### 6.3 File upload, storage and generation workflow

Current flow: `MultipartFile` → `WorkbookFactory.create(input)` → per-row `save()` → stream
discarded → `ImportResult`. There is no stored artefact, so "predefined storage limits" cannot be
measured or enforced. Generation flow: `POST /api/seating/arrange` → solve → **delete all prior
allocations** → insert N rows → return a transient `SeatingResponse`. There is no generation id,
no versioning, no downloadable artefact retained, and no usage event.

Additional concerns: `parsePositiveInt` accepts unbounded dimensions (`I15`, `S_overflow`),
files are parsed in memory with no signature/extension check, and a 10 MB `.xlsx` can expand
substantially during POI parsing. For a metered service you also need idempotency (a retried
`arrange` must not be billed twice) and a durable record of what was delivered.

### 6.4 Scalability and concurrency

**Good:** the solver is fast on realistic loads — 300 students / 10 halls in 183 ms, 400 students
in ~90 ms (`S_scale_*`), deterministic (`E41`), and pre-checks reject most impossible instances
immediately. `open-in-view=false`, `@EntityGraph`/`JOIN FETCH` and DTO mapping inside
transactions show deliberate JPA care.

**Risks for multiple institutions:**

- `SeatingService.arrange` is one long `@Transactional` method: it holds DB connections and locks
  while an exponential solver runs (up to 4 × 500,000 steps, 6–10 s observed). Hikari's default
  pool is 10 connections; a handful of simultaneous paid customers would exhaust it.
- Solver memory/complexity grow with `seats × students` (per-seat `BitSet`s) and
  `gridNeighbors` is O(seats²) (`SeatingArrangementEngine.java:1039-1061`). A 500×500 dual hall
  is 500,000 seats — not viable. Max room dimensions are 500 (REST) but unbounded on import.
- Exports build entire workbooks/PDFs in a `byte[]` in memory; 90,002 styles for a 300×300 hall
  (`X05`). No streaming, no pagination, no async jobs.
- Imports use one DB transaction per row (`I16`: 5,000 rows → 5,000 commits) and inserts use
  `save()` per allocation rather than batching.
- The engine mutates caller-owned rooms and is not thread-safe for shared inputs (`E43`).
- No optimistic locking (`@Version`): two concurrent rounds for one institution can interleave
  delete/insert sequences.

For a subscription product, `arrange` should be a queued, cancellable, tenant-throttled job with a
wall-clock budget, with the DB write phase kept short and atomic.

### 6.5 Security and data-handling risks

| ID | Severity | Finding |
|---|---|---|
| S-01 | **Critical** | `app.security.mode` defaults to `open` (`application.yml:58-61`) and `SecurityConfig.java:63-65` permits **all** `/api/**` requests. Every endpoint — including student PII reads, exports and global deletes — is anonymous by default. Method security (`@PreAuthorize`) is only enabled in `enforced` mode (`MethodSecurityConfig.java:15-17`). |
| S-02 | **Critical** | A non-placeholder default database password is committed as the `DB_PASSWORD` fallback (`application.yml:28`). It is in Git history and must be rotated. |
| S-03 | **High** | `DataSeeder.java:31-51` unconditionally creates three well-known demo accounts with trivially guessable passwords when the `users` table is empty, and logs the credentials at INFO. In a fresh `enforced` deployment these are valid admin credentials. |
| S-04 | **High** | `MockDataController` (`/api/mock/generate`, `/api/mock/clear`, `/api/mock/stats`) and all `ExportController` endpoints have **zero** `@PreAuthorize`. In `enforced` mode any authenticated user — including `INVIGILATOR` — can wipe every room, student and allocation in the database (`MockDataController.java:49-55`, `:143-148`). The frontend even exposes a "Reset all data" button. |
| S-05 | **High** | `csrf.disable()` (`SecurityConfig.java:48`), stateless HTTP Basic only, no rate limiting, no account lockout, no password policy, no MFA, no token/session lifecycle, no TLS enforcement. Adding a cookie/session-based frontend later would inherit a CSRF gap. |
| S-06 | **High** | No tenant isolation anywhere. Even with authentication, `GET /api/students`, `GET /api/seating/allocations` and exports return **all** institutions' data. |
| S-07 | **Medium** | `GlobalExceptionHandler.handleBadInput` returns `ex.getMessage()` (`GlobalExceptionHandler.java:79-83`), `ImportResult.errors` carries raw exception text, and `friendlyMessage` leaks DB-ish messages (`ExcelImportService.java:213-220`). |
| S-08 | **Medium** | Student PII (names, roll/registration numbers) is stored unencrypted; `show-sql: true` (`application.yml:46`) logs SQL; no retention/deletion policy, consent trail, audit log, backup/DR statement, or FERPA/GDPR-style controls. A school-service handling minors needs these. |
| S-09 | **Medium** | Uploads are parsed with no file-type/signature validation, AV scan or decompression budget; `WorkbookFactory.create` accepts whatever bytes arrive. Only a 10 MB multipart cap exists. |
| S-10 | **Medium** | No CORS configuration for the planned *separate* frontend, no API versioning (`/api/...`), no OpenAPI contract, no observability/metrics beyond `INFO` logs. |

### 6.6 Operability / commercial readiness

- No CI workflow, no Maven wrapper, no Docker/compose, no test profile: `mvn test` needs a local
  MySQL and the shipped integration tests wipe the DB. Automated regression safety is effectively
  absent.
- `ddl-auto: update` in production config; no migration tool; `show-sql: true` and
  `format_sql: true` for all environments.
- No LICENSE file, no SLA/uptime design, no health checks beyond Spring defaults, no structured
  logging/tracing, no feature flags, no per-tenant backup/restore.
- Single MySQL instance, no read replicas, no partitioning; all tenants would share one schema
  and one `seating_allocations` table.

### 6.7 Can it accommodate different types of institutions?

**Yes, at the algorithm level.** Free-form `department`/`course`/`yearSession`, configurable
group keys with `ALL`/`ANY` matching, three difficulty presets plus custom gaps, optional subject
codes, single/dual benches, and a header-alias table cover schools (class/section), colleges
(department/batch) and universities (programme/semester) reasonably well. `E16`–`E25` verify
that flexibility, including case/whitespace tolerance and Unicode values.

**No, at the data/architecture level.** Hard-wired bench-pairing attributes (F-16/§5), only two
bench types with uniform halls, three fixed attributes, no exam calendar, no custom fields,
English-only header aliases, hard-coded department colour themes, and global uniqueness all push
institutions toward one canonical data shape. An institution whose roll numbers contain leading
zeros, long numeric IDs, or non-English headers hits F-03/F-09 or needs a custom mapping. A
commercial product needs per-institution field mappings, custom attributes, and configurable
pairing/isolation axes.

---

## 7. Strengths worth preserving

- Deterministic CSP solver with sound feasibility pre-checks, four heuristic profiles,
  forward checking and conflict-directed backjumping; outputs passed an independent verifier.
- Clean separation: engine → service orchestration → controllers → exporters. The engine has no
  Spring/JPA imports beyond entity types, so it can be extracted into a pure stateless library.
- Genuinely configurable separation rules (`groupAttributes`, `groupMatchMode`,
  `conflictScope`, `gaps`, `benchPairingRule`, `horizontalDeptIsolation`) — the main product
  differentiator.
- Per-row isolated Excel import transactions; good rows survive bad ones (`I04`).
- Thoughtful JPA details: `open-in-view=false`, `@EntityGraph`/`JOIN FETCH`, DTO mapping inside
  transactions, explicit bench rebuild with `flush()` on room update.
- Exception mapping to 400/404/409/413/422 with a generic 500 body.
- Three usable output formats (Excel master/hall maps/legend, door-notice PDFs, invigilator PDFs).
- A polished built-in SPA for demos.

---

## 8. Prioritised remediation roadmap (recommendations only — nothing implemented)

**P0 — before any commercial pilot**
1. Rotate the committed DB credential; remove the fallback; force `SECURITY_MODE=enforced`;
   disable demo seeding (or gate behind an explicit dev flag); remove/guard global mock wipe
   endpoints.
2. Introduce `institution_id` on every entity with composite unique keys, and tenant-scope every
   repository query, deletion and export. Add schema migrations (Flyway/Liquibase) and
   `ddl-auto: validate`.
3. Fix F-01 (seat collisions across rounds), F-02 (null-department Excel crash), F-03 (header
   normalisation), F-04 (mixed bench types).

**P1 — enabling the subscription model**
4. Add `Plan`, `Subscription`, `UsageCounter`/`Generation` and `StoredFile` entities; record every
   arrange/import/export as an immutable, idempotent usage event; enforce free-generation,
   student-count and storage quotas before work starts.
5. Add object storage with per-tenant size/retention accounting; validate file signatures and
   decompression budgets; enforce consistent limits across REST and import.
6. Replace global `deleteAllInBatch()` with round-scoped plan versioning; persist round metadata
   so `view()` metrics (F-13) and billing are accurate.
7. Make `arrange` a queued job with wall-clock and step budgets, tenant concurrency limits, and a
   short atomic write phase; add rate limiting and auth on all endpoints, including exports and
   mock endpoints.

**P2 — institutional flexibility**
8. Generalise bench-pairing and horizontal isolation to use the chosen group key; allow per-bench
   layouts and custom attributes; add exam date/session/timeslot; per-institution header mapping
   UI; normalise colours and formula/identifier handling.
9. Fix F-05..F-10, F-12, F-14..F-17; add null-safe validation at the engine/DTO boundary.

**P3 — commercial operations**
10. Add CI with a real (containerised) test DB, H2/test profile, unit tests decoupled from MySQL;
    add OpenAPI, API versioning, CORS for the separate frontend, structured logging/metrics,
    retention/deletion policies, encryption at rest, backup/DR, LICENSE, and a data-processing
    posture appropriate for minors' PII.

---

## 9. Conclusion

As a **single-institution prototype or on-premise tool**, the project works: the solver is
correct, fast on realistic rosters, and flexible enough for schools, colleges and universities at
the algorithm level. It is not yet a reliable production service even for one institution, because
of the multi-subject seat collision, the import/export edge-case crashes, open-by-default
security, and long synchronous solves inside DB transactions.

As the **foundation of a multi-tenant, subscription-based SaaS**, it is incompatible in its
current form: there is no tenant boundary, no stored files, no usage accounting, no plan state,
no per-tenant quotas, and no isolation between institutions. The good news is that the valuable
part — the pure-Java seating engine — is the part that does *not* need to change. Extract it as a
stateless, immutable-input solver, rebuild the surrounding persistence/API/security layer around
tenant identity and metered usage, and the product vision is achievable. Trying to add
subscriptions on top of the existing global tables would produce cross-tenant data leaks and
billing inaccuracies that are much harder to fix later.

---

## Appendix A — Scenario catalogue

Status meaning: **PASS** = behaviour matched expectation (including correct rejections and
constraint-verified plans); **FAIL** = defect reproduced; **INFO** = measurement, stress result or
observation. Timings are from the in-memory harness.


| Case | Status | Time | Outcome |
|---|---|---|---|
| `E01_single_basic` | PASS | 62ms | seated=12 unplaced=0 backtracks=0 slots=12 |
| `E02_dual_basic` | PASS | 10ms | seated=20 unplaced=0 backtracks=0 slots=24 |
| `E03_intermediate` | PASS | 12ms | seated=20 unplaced=0 backtracks=0 slots=20 |
| `E04_advanced` | PASS | 2ms | seated=18 unplaced=0 backtracks=0 slots=36 |
| `E05_zero_gaps` | PASS | 0ms | seated=4 unplaced=0 backtracks=0 slots=4 |
| `E06_custom_gap_3` | PASS | 1ms | seated=12 unplaced=0 backtracks=0 slots=24 |
| `E07_mixed_halls` | PASS | 13ms | seated=30 unplaced=0 backtracks=0 slots=33 |
| `E08_ten_halls_300` | PASS | 163ms | seated=300 unplaced=0 backtracks=0 slots=320 |
| `E09_spare_capacity` | PASS | 0ms | seated=3 unplaced=0 backtracks=0 slots=25 |
| `E10_exact_capacity` | PASS | 0ms | seated=16 unplaced=0 backtracks=0 slots=16 |
| `E11_one_student_1x1` | PASS | 0ms | seated=1 unplaced=0 backtracks=0 slots=1 |
| `E12_zero_students` | PASS | 0ms | seated=0 unplaced=0 backtracks=0 slots=0 |
| `E13_capacity_overflow` | PASS | 0ms | rejected as expected: InsufficientCapacityException: Total capacity of 4 seats is less than 5 students ??? add rooms or switch to DUAL_SEATER benches |
| `E14_single_group_dual_impossible` | PASS | 1ms | rejected as expected: SeatingUnsatisfiableException: Seating is impossible with the current configuration: group 'DEPARTMENT=d0, YEAR_SESSION=y0' has 10 students but can occupy at most 9 seats with current gaps. Reduc... |
| `E15_ANY_mode_strict` | PASS | 0ms | seated=16 unplaced=0 backtracks=0 slots=16 |
| `E16_odd_metadata` | PASS | 2ms | seated=6 unplaced=0 backtracks=0 slots=9 |
| `E17_case_insensitive_groups` | PASS | 0ms | seated=4 unplaced=0 backtracks=0 slots=9 |
| `E18_all_null_attributes` | PASS | 0ms | seated=4 unplaced=0 backtracks=0 slots=16 |
| `E19_key_course` | PASS | 9892ms | rejected as expected: SeatingUnsatisfiableException: No seating layout found after trying 4 deterministic search strategies: no legal candidate for unplaced students in hall 'A' (search strategy 3) |
| `E20_key_ANY_dept_year` | PASS | 0ms | seated=8 unplaced=0 backtracks=0 slots=16 |
| `E21_isolation_off` | PASS | 6654ms | rejected as expected: SeatingUnsatisfiableException: No seating layout found after trying 4 deterministic search strategies: no legal candidate for unplaced students in hall 'A' (search strategy 3) |
| `E22_pairing_dept_only` | PASS | 1ms | seated=24 unplaced=0 backtracks=0 slots=24 |
| `E23_scope_DEPARTMENT` | PASS | 0ms | seated=12 unplaced=0 backtracks=0 slots=12 |
| `E24_scope_YEAR` | PASS | 2ms | seated=12 unplaced=0 backtracks=226 slots=12 |
| `E25_scope_DEPT_OR_YEAR` | PASS | 0ms | seated=8 unplaced=0 backtracks=0 slots=16 |
| `E26_missing_bench_grid` | PASS | 0ms | seated=6 unplaced=0 backtracks=0 slots=9 |
| `E27_partial_bench_grid` | PASS | 0ms | seated=6 unplaced=0 backtracks=0 slots=9 |
| `E28_shuffled_benches` | PASS | 0ms | seated=6 unplaced=0 backtracks=0 slots=9 |
| `E29_mixed_bench_types_in_room` | FAIL | 1ms | CONSTRAINT VIOLATIONS: invalid slot 2 (total 1) |
| `E30_null_room_benchtype` | PASS | 0ms | rejected as expected: IllegalSeatingConfigurationException: Room 'A' must declare a bench type (SINGLE_SEATER / DUAL_SEATER) |
| `E31_blank_hall_name` | PASS | 0ms | rejected as expected: IllegalSeatingConfigurationException: Room hall name must not be blank |
| `E32_zero_rows` | PASS | 0ms | rejected as expected: IllegalSeatingConfigurationException: Room 'A' must have rows > 0 and columns > 0 (got 0x3) |
| `E33_duplicate_hall_names` | PASS | 0ms | seated=6 unplaced=0 backtracks=0 slots=8 |
| `E34_negative_gap` | PASS | 0ms | rejected at SeatingConfig construction: Gap values must be >= 0 (0 disables the axis): dH=-1 dV=0 dD=0 |
| `E35_huge_gap_10_on_small_room` | PASS | 0ms | seated=4 unplaced=0 backtracks=0 slots=4 |
| `S_unsat_latency` | INFO | 8473ms | provably impossible instance (12 students all in one COURSE group) took 8473ms across 4 search profiles before rejection |
| `E43_concurrent_stress` | INFO | 41ms | 320 concurrent arrange() calls over shared mutable room objects -> [clean, 1 exceptions (first=ConcurrentModificationException:null), clean, clean, clean, clean, clean, clean] |
| `E44_ANY_mode_with_missing_department` | INFO | 0ms | solved 8 |
| `E45_group_key_cannot_override_bench_pairing` | INFO | 4ms | solved 12 students but totalSlots=24 (only 12 of 24 physical seats usable; bench pairing hard-wired to department/yearSession, not the chosen COURSE key) |
| `E46_capacity_reported_vs_usable` | INFO | 0ms | 13 students in a 24-seat dual hall rejected: Seating is impossible: department 'onlydept' has 13 students but dual benches can hold at most 12 of any one department. Balance the year/department mix or use SINGLE_SEATE... |
| `E36_duplicate_roll_numbers` | PASS | 0ms | seated=6 unplaced=0 backtracks=0 slots=9 |
| `E37_null_rooms_list` | PASS | 0ms | NPE (unvalidated null list) -> HTTP 500 rather than 400 |
| `E38_null_student_entry` | PASS | 0ms | throws NullPointerException (unvalidated null element) |
| `E39_null_element_in_groupAttributes` | PASS | 0ms | NPE from List.copyOf -> HTTP 500 on malformed JSON |
| `E40_subject_filter_case_sensitive` | PASS | 0ms | exact case-sensitive match seats 1 of 2 students |
| `E41_determinism` | PASS | 0ms | identical layouts across repeated calls |
| `E42_thread_safety_shared_rooms` | INFO | 3ms | 4 concurrent arrange() calls sharing the same mutable ExamRoom objects -> [ok:0, ok:0, ok:0, ok:0]; engine mutates room.getBenches() in place (sort/rebuild) with no defensive copy |
| `S_scale_25` | INFO | 2ms | n=25 halls=1 seats=40 backtracks=0 |
| `S_scale_50` | INFO | 9ms | n=50 halls=2 seats=80 backtracks=0 |
| `S_scale_100` | INFO | 21ms | n=100 halls=3 seats=120 backtracks=0 |
| `S_scale_200` | INFO | 41ms | n=200 halls=5 seats=200 backtracks=0 |
| `S_scale_400` | INFO | 99ms | n=400 halls=10 seats=400 backtracks=0 |
| `S_general_path_25` | INFO | 0ms | ADVANCED general solver n=25 halls=2 seats=40 backtracks=0 |
| `S_general_path_50` | INFO | 2ms | ADVANCED general solver n=50 halls=3 seats=60 backtracks=0 |
| `S_general_path_100` | INFO | 32ms | ADVANCED general solver n=100 halls=5 seats=100 backtracks=42 |
| `S_general_path_200` | INFO | 36ms | ADVANCED general solver n=200 halls=10 seats=200 backtracks=42 |
| `S_dense_hard` | INFO | 0ms | rejected after 0ms: SeatingUnsatisfiableException: Seating is impossible with the current configuration: group 'DEPARTMENT=d1, YEAR_SESSION=y1' has 64 students but can occupy at most 32 seats with current gaps; group ... |
| `S_many_departments` | INFO | 11ms | 40 departments, 100 students -> seated=100 (export palettes have only 7/12 colors) |
| `S_overflow_capacity_math` | INFO | 0ms | 50000x50000 room (import accepts unbounded ints): getTotalBenches()=-1794967296, getCapacity()=705032704 (int overflow) |
| `W01_arrange_and_view` | PASS | 11ms | arrange persisted 16 seats; view returns same plan |
| `W02_two_subject_rounds_collide` | FAIL | 6ms | second paper reuses 8 seats already occupied by first paper; real DB unique (bench_id,slot_number) would reject with 409 |
| `W03_clearExisting_false_without_subject` | PASS | 0ms | rejected AFTER the solver already ran: clearExisting=false requires a subjectCode filter so other rounds are preserved |
| `W04_global_clear_wipes_other_papers` | INFO | 0ms | before=16 allocations; after unfiltered arrange, subjects=[A, B] (previous rounds deleted) |
| `W05_view_metrics_with_subset_rooms` | INFO | 0ms | view reports totalSlots=18 (capacity of BOTH halls) although only hall A was used; unplaced=0 |
| `W07_view_unplaced_metric_mixed_papers` | INFO | 0ms | view(null) reports unplaced=6 (counts ALL 12 students against 6 saved seats); view('A') reports unplaced=0 |
| `W08_room_subset_clears_other_halls` | INFO | 0ms | arrange(roomIds=[A]) saved 6 seats in A; arranging only B wipes them (A now has 0) because clearExisting=true always deleteAllInBatch() |
| `W06_no_rooms` | PASS | 0ms | 404 as designed: No exam halls found ??? create a room first. |
| `I01_standard_headers` | PASS | 7ms | 2 rows imported |
| `I02_alias_headers_case_and_punctuation` | PASS | 1ms | alias mapping worked; dept=XII-A year=Sem-4 |
| `I02b_trailing_dot_header_rejected` | FAIL | 1ms | README says 'Roll No.' is accepted, but normalizeHeader leaves a trailing space ('roll no ') so it is REJECTED: Missing column in Excel header: 'roll no' (accepted spellings include: roll no, roll number, rollno, reg ... |
| `I02c_leading_dot_header_rejected` | FAIL | 0ms | leading punctuation becomes a leading space -> header unmatched |
| `I03_unknown_header_fails_fast` | PASS | 0ms | 400: Missing column in Excel header: 'name' (accepted spellings include: name, student name, full name) |
| `I04_bad_rows_isolated` | PASS | 1ms | 2 good rows kept, 2 bad rows reported: [Row 3: Name is required for roll 2, Row 4: duplicate value for a unique field (roll number or hall name already exists) ??? row skipped] |
| `I05_numeric_roll_and_decimal` | INFO | 3ms | imported=3 rolls=[1001, 1002.5, 9007199254740992] |
| `I06_formula_cell_cached_value` | INFO | 0ms | formula cells import their CACHED results (not the formula): imported=1 values=[Asha/1001]; files with no cached result yield blank rows |
| `I07_overlong_fields_not_validated` | FAIL | 1ms | @Size(max=100/30) is NOT applied on import; DB length error is misreported as 'duplicate value for a unique field': [Row 2: duplicate value for a unique field (roll number or hall name already exists) ??? row skipped] |
| `I08_room_import_bounds` | INFO | 0ms | imported=2 errors=[Row 2: Rows must be positive (got '0'), Row 3: Rows must be positive (got '-2')] (benchType uses loose contains(); no @Max(500) enforcement on import) |
| `I09_duplicate_hall_name` | PASS | 0ms | duplicate row reported and skipped |
| `I10_blank_rows_and_null_rows` | PASS | 0ms | blank/null rows skipped |
| `I11_missing_header_row` | PASS | 0ms | 400: Missing column in Excel header: 'name' (accepted spellings include: name, student name, full name) |
| `I13_leading_zero_and_whitespace_rolls` | INFO | 0ms | imported=3 rolls=[00123, 00456, 1 2 3] (internal whitespace collapsed) |
| `I15_room_import_over_500_not_blocked` | INFO | 0ms | REST create enforces @Max(500) but Excel import does not: rows=600 imported=1, benches built=1200 |
| `I16_bulk_import_5000_rows` | INFO | 358ms | 5000 rows -> imported=5000 errors=0 (one DB transaction per row = 5000 commits in production) |
| `I12_global_roll_uniqueness` | INFO | 0ms | second institution reusing roll 1001 -> imported=0 errors=[Row 2: duplicate value for a unique field (roll number or hall name already exists) ??? row skipped] (global uk_students_roll_no, no tenant scope) |
| `X01_null_department` | FAIL | 15ms | export with an allowed null department throws NullPointerException (styleForDept calls dept.hashCode()) |
| `X02_empty_plan` | PASS | 0ms | empty plan exports cleanly (9 bytes) |
| `X03_style_explosion` | INFO | 8ms | 100x100 hall, 1 seated student: created 10002 CellStyle objects (one per empty cell); Excel's limit is 64,000 styles |
| `X05_style_limit_exceeded` | INFO | 40ms | 300x300 hall (a legal 500x500 REST room) created 90002 styles: exceeds Excel's 64,000 style cap by 26002; workbook may be corrupt/unopenable |
| `X04_sheet_name_collisions` | INFO | 0ms | 3 halls whose sanitized names truncate to the same 31 chars -> uniqueSheetName adds suffixes; export returned 9 bytes |
| `P01_null_metadata_pdf` | PASS | 71ms | door notices + invigilator sheets tolerate null department/year (unlike Excel export) |
| `P02_hash_overflow_department` | FAIL | 0ms | department 'polygenelubricants' (hashCode=-2147483648) crashes PDF export with ArrayIndexOutOfBoundsException because Math.abs(Integer.MIN_VALUE) stays negative -> negative palette index |
| `P03_null_hall_name_pdf` | FAIL | 0ms | null hallName throws NullPointerException in buildHallBanner().toUpperCase() |

**Totals:** 52 PASS / 8 FAIL / 32 INFO = 92 scenarios.

The harness, its in-memory stand-ins and the raw TSV results were kept outside the repository
(`/tmp/harness`) and are not part of this commit. This assessment adds documentation only; no
production code, configuration, tests or dependencies were modified.
