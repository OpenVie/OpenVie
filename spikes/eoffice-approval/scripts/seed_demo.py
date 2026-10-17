#!/usr/bin/env python3
"""Deploy the Issue #7 BPMN and start both demo documents using Flowable REST."""
from __future__ import annotations
import base64, json, os, pathlib, urllib.error, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = os.getenv("FLOWABLE_BASE_URL", "http://localhost:18081/flowable-rest/service").rstrip("/")
USER = os.getenv("FLOWABLE_REST_USER", "rest-admin")
PASSWORD = os.getenv("FLOWABLE_REST_PASSWORD", "local-only-change-me")


def request(path: str, method="GET", payload=None, files=None):
    url = f"{BASE}/{path.lstrip('/')}"
    headers = {"Accept": "application/json"}
    token = base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()
    headers["Authorization"] = f"Basic {token}"
    data = None
    if files:
        boundary = "----OpenVieIssue7Boundary"
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        name, content = files
        data = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\nContent-Type: application/xml\r\n\r\n".encode() + content + f"\r\n--{boundary}--\r\n".encode())
    elif payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            body = response.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode(errors="replace")
        raise RuntimeError(f"Flowable {method} {path} failed ({exc.code}): {detail}") from exc


def main():
    bpmn = ROOT / "bpmn" / "eoffice-document-approval.bpmn20.xml"
    deployment = request("repository/deployments", "POST", files=(bpmn.name, bpmn.read_bytes()))
    print(f"Deployed BPMN: {deployment.get('id', '(id missing)')}")
    documents = json.loads((ROOT / "data" / "demo-documents.json").read_text(encoding="utf-8"))
    for document in documents:
        variables = [
            {"name": "documentId", "value": document["documentId"], "type": "string"},
            {"name": "documentTitle", "value": document["title"], "type": "string"},
            {"name": "documentText", "value": document["content"], "type": "string"},
            {"name": "target", "value": document["target"], "type": "string"},
            {"name": "workspaceId", "value": document["workspaceId"], "type": "string"},
            {"name": "organizationId", "value": document["organizationId"], "type": "string"},
            {"name": "reminderDuration", "value": os.getenv("APPROVAL_REMINDER_DURATION", "PT24H"), "type": "string"},
            {"name": "escalationDuration", "value": os.getenv("APPROVAL_ESCALATION_DURATION", "PT48H"), "type": "string"},
            {"name": "approvalOutcome", "value": "PENDING", "type": "string"},
            {"name": "rejectReason", "value": "", "type": "string"},
        ]
        instance = request("runtime/process-instances", "POST", {"processDefinitionKey": "eofficeDocumentApproval", "variables": variables})
        print(f"Started {document['documentId']}: processInstanceId={instance.get('id', '(id missing)')} expectedSensitive={document['expectedSensitive']}")
    print("Start complete. Run worker.py in another terminal; both branches should now progress to their role-based tasks.")

if __name__ == "__main__":
    main()
