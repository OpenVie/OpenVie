#!/usr/bin/env python3
"""Minimal Flowable external-job worker for the Issue #7 demo.

The worker intentionally reuses rag-chatbot-fastapi/app/modules/generation/internal/sensitive_policy.py;
it does not maintain a second keyword vocabulary.
"""
from __future__ import annotations
import base64, importlib.util, json, os, pathlib, time, urllib.error, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
POLICY_FILE = REPO / "rag-chatbot-fastapi/app/modules/generation/internal/sensitive_policy.py"
spec = importlib.util.spec_from_file_location("openvie_sensitive_policy", POLICY_FILE)
policy = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(policy)
BASE = os.getenv("FLOWABLE_BASE_URL", "http://localhost:18081/flowable-rest/service").rstrip("/")
USER = os.getenv("FLOWABLE_REST_USER", "rest-admin")
PASSWORD = os.getenv("FLOWABLE_REST_PASSWORD", "local-only-change-me")
WORKER_ID = os.getenv("FLOWABLE_WORKER_ID", "openvie-issue7-worker")
TOPICS = ["score-sensitivity", "send-reminder", "audit-escalation", "audit-rejection", "publish-document"]


def call(path, method="GET", payload=None):
    headers = {"Accept": "application/json"}
    headers["Authorization"] = "Basic " + base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode()
    req = urllib.request.Request(f"{BASE}/{path.lstrip('/')}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            raw = response.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"Flowable {method} {path}: HTTP {exc.code}: {exc.read().decode(errors='replace')}") from exc


def variables_for(instance_id):
    data = call(f"runtime/process-instances/{instance_id}/variables")
    return {v.get("name"): v.get("value") for v in data}


def complete(job, variables):
    job_id = job.get("id") or job.get("jobId")
    call(f"acquire/jobs/{job_id}/complete", "POST", {"workerId": WORKER_ID, "variables": [
        {"name": key, "value": value, "type": "boolean" if isinstance(value, bool) else "string"}
        for key, value in variables.items()
    ]})


def handle(topic, job):
    instance_id = job.get("processInstanceId") or job.get("processInstance", {}).get("id")
    if not instance_id:
        raise ValueError(f"External job has no processInstanceId: {job}")
    variables = variables_for(instance_id)
    result = {}
    if topic == "score-sensitivity":
        content = str(variables.get("documentText", ""))
        target = str(variables.get("target", "workspace"))
        sensitive = bool(policy.is_sensitive_query(content)) or target in {"shared", "public"}
        result = {"sensitive": sensitive, "policyVersion": policy.POLICY_VERSION,
                  "approvalLevel": 2 if sensitive else 1,
                  "auditLastEvent": f"sensitivity-scored:{policy.POLICY_VERSION}:level-{2 if sensitive else 1}"}
        print(f"Scored {variables.get('documentId')}: sensitive={sensitive}, level={result['approvalLevel']}, policy={policy.POLICY_VERSION}")
    elif topic == "send-reminder":
        result = {"auditLastEvent": "reminder-sent", "reminderSent": True}
        print(f"REMINDER: document {variables.get('documentId')} is still awaiting level-1 approval")
    elif topic == "audit-escalation":
        result = {"auditLastEvent": "overdue, escalated", "escalated": True, "approvalLevel": 2}
        print(f"AUDIT: document {variables.get('documentId')} overdue, escalated to ORG_OWNER")
    elif topic == "audit-rejection":
        result = {"documentStatus": "REJECTED", "auditLastEvent": "document-rejected"}
        print(f"AUDIT: document {variables.get('documentId')} rejected; reason={variables.get('rejectReason', '')}")
    elif topic == "publish-document":
        # Demo boundary only: the production ingestion adapter must replace this log action.
        result = {"documentStatus": "APPROVED", "auditLastEvent": "approved-for-ingestion"}
        print(f"PUBLISH DEMO: {variables.get('documentId')} approved for normal ingestion pipeline")
    complete(job, result)


def main():
    print(f"OpenVie Issue #7 worker started; policy version={policy.POLICY_VERSION}; topics={', '.join(TOPICS)}")
    while True:
        did_work = False
        for topic in TOPICS:
            try:
                # Flowable external-job REST accepts topic, workerId and lockDuration for acquisition.
                response = call("acquire/jobs", "POST", {"workerId": WORKER_ID, "topic": topic, "lockDuration": 60000})
                jobs = response if isinstance(response, list) else response.get("data", response.get("jobs", []))
                for job in jobs:
                    handle(topic, job)
                    did_work = True
            except (urllib.error.URLError, TimeoutError, RuntimeError, ValueError, KeyError) as exc:
                print(f"Worker poll/handle warning for {topic}: {exc}")
        if not did_work:
            time.sleep(float(os.getenv("FLOWABLE_WORKER_POLL_SECONDS", "2")))

if __name__ == "__main__":
    main()
