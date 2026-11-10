# Smoke tests

Two scripts that exercise the whole product against a **running backend**.

```bash
# the backend must be up on :8092 (see the header of the script)
python3 scripts/smoke-test.py       # the whole flow + the platform administrator
python3 scripts/smoke-import.py     # the roster upload with its identity conversation
```

`smoke-test.py` covers: register, add students, add a hall, generate a plan,
move a student by hand, export, the usage counters, the administrator's view,
raising and lowering a limit, suspending and waking an institution, and
signing out.

`smoke-import.py` builds an `.xlsx` in memory — a sheet with **no roll number
at all**, where the students are identified by an "Application ID" column —
and checks that the preview suggests it, that the import uses it, that the
choice is remembered, and that a second import creates no duplicates.
