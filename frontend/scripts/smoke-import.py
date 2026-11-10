#!/usr/bin/env python3
"""The roster upload: analyse the sheet, then import with the chosen identity."""
import json
import os
import urllib.request
import urllib.error
import uuid

BASE = os.environ.get("API_BASE", "http://localhost:8092")


def call(method, path, body=None, token=None, expect=None):
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
        raise SystemExit(f"FAIL {method} {path} -> {status} (wanted {expect}): {payload[:600]}")
    try:
        return status, json.loads(payload) if payload else None
    except Exception:
        return status, payload


def multipart(path, fields, files, token=None):
    """Sends a multipart/form-data body — fields and files."""
    boundary = "----exam" + uuid.uuid4().hex
    body = b""
    for name, value in fields.items():
        body += f"--{boundary}\r\n".encode()
        body += f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode()
        body += f"{value}\r\n".encode()
    for name, (filename, content) in files.items():
        body += f"--{boundary}\r\n".encode()
        body += f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'.encode()
        body += b"Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n"
        body += content + b"\r\n"
    body += f"--{boundary}--\r\n".encode()

    request = urllib.request.Request(BASE + path, data=body, method="POST")
    request.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def build_xlsx(headers, rows):
    """A minimal .xlsx written by hand — no third-party library needed."""
    import io
    import zipfile

    def cell(ref, value):
        if isinstance(value, (int, float)):
            return f'<c r="{ref}"><v>{value}</v></c>'
        return f'<c r="{ref}" t="inlineStr"><is><t>{value}</t></is></c>'

    sheet_rows = []
    for index, row in enumerate([headers] + rows, start=1):
        cells = "".join(
            cell(f"{chr(ord('A') + column)}{index}", value)
            for column, value in enumerate(row)
        )
        sheet_rows.append(f'<row r="{index}">{cells}</row>')

    sheet = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        f'<sheetData>{"".join(sheet_rows)}</sheetData></worksheet>'
    )
    content_types = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        '</Types>'
    )
    root_rels = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
        '</Relationships>'
    )
    workbook = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        '<sheets><sheet name="Roster" sheetId="1" r:id="rId1"/></sheets></workbook>'
    )
    workbook_rels = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
        '</Relationships>'
    )

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", content_types)
        archive.writestr("_rels/.rels", root_rels)
        archive.writestr("xl/workbook.xml", workbook)
        archive.writestr("xl/_rels/workbook.xml.rels", workbook_rels)
        archive.writestr("xl/worksheets/sheet1.xml", sheet)
    return buffer.getvalue()


# A fresh institution for every run, so the test never sees its own
# leftovers.
stamp = str(abs(hash(str(__import__("time").time()))) % 100000)
slug = f"import-test-{stamp}"
status, session = call("POST", "/api/v1/auth/register", {
    "institutionName": "Import Test College",
    "slug": slug,
    "adminUsername": f"importer{stamp}",
    "adminPassword": "importer-secret-1",
}, expect=201)
token = session["token"]
print(f"institution ready, token {token[:12]}…")

print()
print("=== A sheet with no roll number at all — students identified by application id ===")
rows = [
    ["Application ID", "Student Name", "Course", "Hostel Block"],
    ["APP-001", "Aditi Sharma", "BSc", "H1"],
    ["APP-002", "Bilal Khan", "BSc", "H1"],
    ["APP-003", "Chen Wei", "BCA", "H2"],
    ["APP-004", "Divya Rao", "BCA", "H2"],
    ["APP-005", "Esha Patel", "BCom", "H3"],
]
xlsx = build_xlsx(["Application ID", "Student Name", "Course", "Hostel Block"], rows[1:])

status, preview = multipart(
    "/api/v1/students/imports/preview",
    {},
    {"file": ("roster.xlsx", xlsx)},
    token=token,
)
print(f"  status {status}")
print(f"  totalRows       : {preview['totalRows']}")
print("  columns:")
for column in preview["columns"]:
    print(f"    - {column['title']!r} key={column['key']!r} kind={column['kind']} "
          f"unique={column['unique']} complete={column['complete']} "
          f"distinct={column['distinctValues']} samples={column['samples']}")
print("  suggestedIdentities:")
for suggestion in preview["suggestedIdentities"]:
    print(f"    - {suggestion['label']!r} attributes={suggestion['attributes']} unique={suggestion['unique']}")
assert preview["totalRows"] == 5, preview

chosen = preview["suggestedIdentities"][0]["attributes"]
print(f"  -> choosing {chosen}")

print()
print("=== The import ===")
status, result = multipart(
    "/api/v1/students/imports",
    {"identityAttributes": ",".join(chosen)},
    {"file": ("roster.xlsx", xlsx)},
    token=token,
)
print(f"  status {status}")
print(f"  imported {result['imported']} / {result['totalRows']}")
if result.get("notes"):
    print(f"  notes   {result['notes']}")
if result.get("errors"):
    print(f"  errors  {result['errors']}")
assert result["imported"] == 5, result
assert not result.get("errors"), result

print()
print("=== The students landed, identified by the chosen attribute ===")
status, students = call("GET", "/api/v1/students?page=0&size=10", token=token, expect=200)
for student in students["content"]:
    print(f"    {student.get('identityKey'):10} {student['name']:15} "
          f"attributes={student.get('attributes')}")
assert students["totalElements"] == 5, students
assert students["content"][0]["identityKey"].startswith("APP-"), students["content"][0]
step = "identified by application id, with the course and hostel kept"

print()
print("=== The chosen identity is remembered for the institution ===")
# What the frontend does after the import: the choice becomes the standing
# rule, so the next roster is read the same way.
status, saved = call("PUT", "/api/v1/students/identity", {
    "label": "Application ID",
    "attributes": chosen,
}, token=token, expect=200)
print(f"  saved: {saved}")
status, scheme = call("GET", "/api/v1/students/identity", token=token, expect=200)
print(f"  read back: {scheme}")
assert scheme["attributes"] == chosen, scheme
assert scheme["displayAttribute"] == "applicationid", scheme

print()
print("=== A second import needs no choice — the rule applies ===")
status, result = multipart(
    "/api/v1/students/imports",
    {},
    {"file": ("roster.xlsx", xlsx)},
    token=token,
)
print(f"  imported {result['imported']} / {result['totalRows']}  notes={result.get('notes')}")
for note in result.get("notes") or []:
    print(f"    note: {note}")
# The same file a second time: every row is already on file, so nothing is
# duplicated — the rule recognised the students it had already imported.
assert result["imported"] == 0, result
assert all("already on file" in error for error in result["errors"]), result
status, students = call("GET", "/api/v1/students?page=0&size=50", token=token, expect=200)
assert students["totalElements"] == 5, students
step = "the second import created no duplicates"

print()
print("=== The rule can be cleared again ===")
status, _ = call("DELETE", "/api/v1/students/identity", token=token, expect=204)
status, scheme = call("GET", "/api/v1/students/identity", token=token, expect=200)
assert scheme["attributes"] == [], scheme
print(f"  cleared: {scheme}")

print()
print("=== A hall to seat them in ===")
call("POST", "/api/v1/rooms", {
    "hallName": "Block C", "rows": 3, "columns": 3, "benchType": "DUAL_SEATER",
}, token=token, expect=201)
step = "a 3x3 dual-seater hall"

print()
print("=== Grouping by the dynamic attribute ===")
status, plan = call("POST", "/api/v1/seating-plans", {
    "strictness": "BASIC",
    "conflictScope": "DEPARTMENT_AND_YEAR",
    "clearExisting": True,
    "groupAttributes": [{"key": "hostelblock"}],
}, token=token, expect=201)
print(f"  seated={plan['seated']} unplaced={plan['unplaced']} solved={plan['solved']}")
assert plan["seated"] == 5, plan

print()
print("ALL IMPORT CHECKS PASSED")
