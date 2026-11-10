#!/usr/bin/env python3
"""End-to-end smoke test of the whole product flow, against the live backend."""
import json
import os
import time
import urllib.request
import urllib.error

BASE = os.environ.get("API_BASE", "http://localhost:8092")


def call(method, path, body=None, token=None, expect=None, raw=False):
    url = BASE + path
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request) as response:
            payload = response.read()
            status = response.status
    except urllib.error.HTTPError as error:
        payload = error.read()
        status = error.code
    if expect is not None and status != expect:
        raise SystemExit(f"FAIL {method} {path} -> {status} (wanted {expect}): {payload[:400]}")
    if raw:
        return status, payload
    try:
        return status, json.loads(payload) if payload else None
    except Exception:
        return status, payload[:200]


ok = []


def step(label):
    ok.append(label)
    print(f"  ok  {label}")


print("=== 1. Register an institution ===")
# A fresh institution for every run, so the test never sees its own
# leftovers.
stamp = str(int(time.time() * 1000) % 1000000)
status, session = call("POST", "/api/v1/auth/register", {
    "institutionName": "Riverside University",
    "slug": f"riverside-{stamp}",
    "adminUsername": f"registrar{stamp}",
    "adminPassword": "registrar-secret-1",
    "adminEmail": "registrar@riverside.example",
}, expect=201)
token = session["token"]
step(f"registered, token {token[:12]}…")

print("=== 2. The institution sees itself ===")
status, me = call("GET", "/api/v1/auth/me", token=token, expect=200)
assert me["institution"]["name"] == "Riverside University", me
step(f"institution = {me['institution']['name']}, role = {me['user']['role']}")

print("=== 3. Add students ===")
for index in range(1, 9):
    call("POST", "/api/v1/students", {
        "name": f"Student {index:02d}",
        "rollNo": f"R{index:03d}",
        "department": "Engineering" if index % 2 else "Arts",
        "yearSession": "2026",
        "subjectCode": "CS101",
        "attributes": {"batch": "A" if index <= 4 else "B"},
    }, token=token, expect=201)
step("8 students added by hand")

print("=== 4. Add a hall ===")
status, room = call("POST", "/api/v1/rooms", {
    "hallName": "Main Hall A", "rows": 4, "columns": 4, "benchType": "SINGLE_SEATER",
}, token=token, expect=201)
step(f"hall {room['hallName']} with {room['rows']}x{room['columns']} benches")

print("=== 5. Generate a plan ===")
status, plan = call("POST", "/api/v1/seating-plans", {
    "strictness": "BASIC",
    "conflictScope": "DEPARTMENT_AND_YEAR",
    "subjectCode": "CS101",
    "clearExisting": True,
    "groupAttributes": [{"key": "batch"}],
}, token=token, expect=201)
print(f"      seated={plan['seated']} unplaced={plan['unplaced']} solved={plan['solved']}")
assert plan["seated"] == 8, plan
step("all 8 students seated")

print("=== 6. Read the plan back ===")
status, allocations = call("GET", "/api/v1/seating-plans/allocations?page=0&size=50", token=token, expect=200)
assert allocations["totalElements"] == 8, allocations
first = allocations["content"][0]
step(f"{allocations['totalElements']} allocations, first = {first['name']} in {first['hallName']}")

print("=== 7. Move one student by hand ===")
status, moved = call("PATCH", f"/api/v1/seating-plans/allocations/{first['allocationId']}", {
    "roomId": room["id"], "benchRow": 3, "benchCol": 2, "slot": 1,
}, token=token, expect=200)
step("manual move accepted")

print("=== 8. Exports ===")
status, blob = call("GET", "/api/v1/exports/excel", token=token, expect=200, raw=True)
assert blob[:2] == b"PK", blob[:20]
step(f"excel export {len(blob)} bytes (xlsx)")
status, blob = call("GET", "/api/v1/exports/pdf/door-notices", token=token, expect=200, raw=True)
assert blob[:4] == b"%PDF", blob[:20]
step(f"pdf export {len(blob)} bytes")

print("=== 9. Usage and quota ===")
status, usage = call("GET", "/api/v1/usage", token=token, expect=200)
print(f"      students {usage['studentsUsed']}/{usage['studentsLimit']} "
      f"rooms {usage['roomsUsed']}/{usage['roomsLimit']} "
      f"generations {usage['generationsUsed']}/{usage['generationsLimit']}")
step("usage reported against the FREE plan")

print("=== 10. The platform administrator ===")
status, admin_status = call("GET", "/api/v1/admin/status", expect=200)
assert admin_status["enabled"], admin_status
step(f"admin enabled, username = {admin_status['username']}")

status, admin_session = call("POST", "/api/v1/auth/login", {
    "username": "platformadmin", "password": "platform-secret-123",
}, expect=200)
admin_token = admin_session["token"]
assert admin_session["user"]["role"] == "PLATFORM_ADMIN"
step("administrator signed in")

print("=== 11. An ordinary customer may not open the operator's view ===")
for path in ["/api/v1/admin/institutions", "/api/v1/admin/users", "/api/v1/admin/usage"]:
    status, _ = call("GET", path, token=token)
    assert status in (401, 403), f"{path} -> {status}"
step("customer refused on all three operator endpoints")

status, _ = call("GET", "/api/v1/admin/institutions")
assert status in (401, 403)
step("anonymous refused too")

print("=== 12. The operator sees every institution ===")
status, institutions = call("GET", "/api/v1/admin/institutions", token=admin_token, expect=200)
names = [item["name"] for item in institutions]
print(f"      {names}")
assert f"riverside-{stamp}" in [i["slug"] for i in institutions], names
step(f"{len(institutions)} institution(s) listed")

status, users = call("GET", "/api/v1/admin/users", token=admin_token, expect=200)
print(f"      accounts: {[u['username'] for u in users]}")
assert any(u["username"].startswith("registrar") for u in users), users
step(f"{len(users)} account(s) listed")

status, platform_usage = call("GET", "/api/v1/admin/usage", token=admin_token, expect=200)
print(f"      students={platform_usage['students']} halls={platform_usage['halls']} "
      f"generations={platform_usage['generations']} seats={platform_usage['seats']}")
# The total covers every institution, so it is at least this one's eight.
assert platform_usage["students"] >= 8, platform_usage
assert platform_usage["halls"] >= 1, platform_usage
assert platform_usage["generations"] >= 1, platform_usage
step("the operator sees what every institution generates")

print("=== 13. Raising a limit the customer is blocked by ===")
target = [i for i in institutions if i["slug"] == f"riverside-{stamp}"][0]
before = target["studentsLimit"]
status, changed = call("PUT", f"/api/v1/admin/institutions/{target['id']}/subscription", {
    "planCode": "PRO",
    "limits": {
        "maxStudents": 500, "maxRooms": 40, "maxGenerationsPerMonth": 99,
        "storageLimitMb": 2048, "maxFileSizeMb": 64, "seatsPerGeneration": 400,
    },
}, token=admin_token, expect=200)
print(f"      studentsLimit {before} -> {changed['studentsLimit']}, plan {changed['planCode']}")
assert changed["studentsLimit"] == 500 and changed["planCode"] == "PRO"
step("plan switched and limits raised")

print("=== 14. The customer is no longer blocked ===")
status, usage = call("GET", "/api/v1/usage", token=token, expect=200)
print(f"      students {usage['studentsUsed']}/{usage['studentsLimit']} "
      f"rooms {usage['roomsUsed']}/{usage['roomsLimit']}")
assert usage["studentsLimit"] == 500 and usage["roomsLimit"] == 40
step("the new limits are in force for the customer")

print("=== 15. Lowering a limit again ===")
status, changed = call("PUT", f"/api/v1/admin/institutions/{target['id']}/subscription", {
    "planCode": "STARTER",
    "limits": {"maxStudents": None, "maxRooms": None, "maxGenerationsPerMonth": None,
               "storageLimitMb": None, "maxFileSizeMb": None, "seatsPerGeneration": None},
}, token=admin_token, expect=200)
print(f"      studentsLimit {changed['studentsLimit']}, plan {changed['planCode']}")
status, usage = call("GET", "/api/v1/usage", token=token, expect=200)
assert usage["studentsLimit"] == changed["studentsLimit"]
step("the override was cleared and the plan's numbers apply again")

print("=== 16. Suspending and waking an institution ===")
status, suspended = call("PUT", f"/api/v1/admin/institutions/{target['id']}/status?status=SUSPENDED",
                         token=admin_token, expect=200)
assert suspended["status"] == "SUSPENDED"
status, body = call("POST", "/api/v1/auth/login",
                    {"username": f"registrar{stamp}", "password": "registrar-secret-1"})
assert status == 401, f"suspended institution could still sign in: {status} {body}"
step("a suspended institution can no longer sign in")

call("PUT", f"/api/v1/admin/institutions/{target['id']}/status?status=ACTIVE", token=admin_token, expect=200)
status, _ = call("POST", "/api/v1/auth/login",
                 {"username": f"registrar{stamp}", "password": "registrar-secret-1"}, expect=200)
step("and can sign in again once activated")

print("=== 17. An operator may not demote themselves ===")
status, me_admin = call("GET", "/api/v1/auth/me", token=admin_token, expect=200)
status, body = call("PUT", f"/api/v1/admin/users/{me_admin['user']['id']}",
                    {"role": "EXAM_CELL"}, token=admin_token)
assert status == 400, f"self-demotion was allowed: {status} {body}"
step("self-demotion refused")

print("=== 18. Signing out ===")
status, _ = call("POST", "/api/v1/auth/logout", token=token, expect=204)
status, _ = call("GET", "/api/v1/auth/me", token=token)
assert status == 401
step("the session ended with the sign-out")

print()
print(f"ALL {len(ok)} CHECKS PASSED")
