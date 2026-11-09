#!/usr/bin/env python3
# Copyright 2026 LinkedNodeDigital
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Evaluate LLM candidates for BizApp intent classification and manifest drafting.

The draft system prompt is read from BizAppDraftService.java so this harness
always exercises the prompt that production uses. Intent cases use the
classifier's documented contract (installed apps: crm, booking,
signature-request).

Usage:
  python scripts/ai/model_eval.py --models vylinh,qwen2.5:3b
  python scripts/ai/model_eval.py --models vylinh --only draft --save-raw target/ai-eval
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[2]
DRAFT_SERVICE = ROOT / "api/src/main/java/com/openvie/api/bizapp/service/BizAppDraftService.java"
BIZAPP_SCHEMA = ROOT / "contracts/bizapp/bizapp.v1.schema.json"
CASES = ROOT / "scripts/ai/golden_cases.json"

INTENT_SCHEMA: dict[str, Any] = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "required": ["intent", "confidence"],
    "properties": {
        "intent": {
            "type": "string",
            "enum": [
                "NONE", "LIST_APPS", "APP_DETAIL", "APP_DATA", "INSTALL", "UNINSTALL",
                "SUGGEST", "CREATE_APP", "APP_RECORD_CREATE", "APP_RECORD_UPDATE",
                "APP_RECORD_ARCHIVE", "APP_RECORD_SUBMIT",
            ],
        },
        "appKind": {"type": "string"},
        "entity": {"type": "string"},
        "fields": {"type": "object"},
        "confidence": {"type": "number", "minimum": 0.0, "maximum": 1.0},
        "reply": {"type": "string"},
        "reason": {"type": "string"},
    },
}

INTENT_SYSTEM = """You are OpenVie's Conversational BizApp intent classifier.
Classify the user prompt into exactly ONE intent:
- LIST_APPS: user wants to view or list installed apps
- APP_DETAIL: user wants details about a specific app manifest or configuration
- APP_DATA: user asks about records or domain data inside an app
- INSTALL: user wants to install an app from the catalog
- UNINSTALL: user wants to uninstall/remove an installed app
- SUGGEST: user asks for app suggestions/recommendations
- CREATE_APP: user explicitly wants to build a new app, or asks how to manage/track a new domain that no installed or marketplace app covers.
- APP_RECORD_CREATE: user wants to create/add/insert a new record/data into an app
- APP_RECORD_UPDATE: user wants to update/edit an existing record in an app
- APP_RECORD_ARCHIVE: user wants to delete/archive a record in an app
- APP_RECORD_SUBMIT: user wants to submit a record for approval
- NONE: document questions, guidelines/policy questions, or chit-chat unrelated to any business operation

Installed Apps: [crm, booking, signature-request]
Marketplace Catalog: [crm, booking, signature-request]
Caller Role: ORG_OWNER
Locale: vi

Guardrails:
- NEVER return INSTALL for an app already listed under Installed Apps, and NEVER return UNINSTALL for an app that is NOT in Installed Apps.
- Polite capability requests that match an installed app's domain ARE that app's record/data intent, not general conversation.
- If an installed app covers the user's operational request, use its record/data intent; never answer an operational request as a general document question.
- Return NONE only for document questions, procedures/policies ("cách", "quy trình", "làm sao"...), or chit-chat unrelated to every business operation.

Output JSON only, matching the provided schema.
"""


def extract_draft_system_prompt() -> str:
    source = DRAFT_SERVICE.read_text(encoding="utf-8")
    match = re.search(r'String systemPrompt = """(.*?)""";', source, re.DOTALL)
    if not match:
        raise SystemExit(f"Cannot find the draft system prompt in {DRAFT_SERVICE}")
    lines = match.group(1).splitlines()
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    indents = [len(line) - len(line.lstrip()) for line in lines if line.strip()]
    indent = min(indents) if indents else 0
    text = "\n".join(line[indent:] if line.strip() else "" for line in lines)
    # Java text block: \\ -> \, \" -> ". We only need the doubled backslash.
    return text.replace("\\\\", "\\")


@dataclass
class CaseResult:
    case_id: str
    model: str
    task: str
    latency_s: float
    json_ok: bool = False
    schema_ok: bool | None = None
    passed: list[str] = field(default_factory=list)
    failed: list[str] = field(default_factory=list)
    output: str = ""


def call_ollama(base_url: str, model: str, system: str, user: str,
                schema: dict[str, Any], temperature: float, num_predict: int,
                timeout: int) -> tuple[str, float]:
    body = json.dumps({
        "model": model,
        "stream": False,
        "format": schema,
        "options": {"temperature": temperature, "num_predict": num_predict},
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
    }).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/chat", data=body,
        headers={"Content-Type": "application/json"},
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.load(response)
    except urllib.error.URLError as error:
        raise SystemExit(f"Ollama is unreachable at {base_url}: {error}") from error
    latency = time.perf_counter() - started
    return payload.get("message", {}).get("content", ""), latency


def parse_json(text: str) -> Any:
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        start, end = text.find("{"), text.rfind("}")
        if start < 0 or end <= start:
            raise
        return json.loads(text[start:end + 1])


def blocks(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    return [item for item in manifest.get("blocks", []) if isinstance(item, dict)]


def entities(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    return [item for item in manifest.get("entities", []) if isinstance(item, dict)]


def approval_block(manifest: dict[str, Any]) -> dict[str, Any] | None:
    found = [item for item in blocks(manifest) if item.get("type") == "approval_workitem"]
    return found[0] if found else None


def hook_targets(manifest: dict[str, Any]) -> list[str]:
    targets: list[str] = []
    for entity in entities(manifest):
        hooks = entity.get("hooks")
        if not isinstance(hooks, dict):
            continue
        for value in hooks.values():
            if isinstance(value, list):
                targets.extend(str(target) for target in value)
    return targets


def check_approval_block(manifest: dict[str, Any]) -> bool:
    return len([b for b in blocks(manifest) if b.get("type") == "approval_workitem"]) == 1


def check_approval_hooked(manifest: dict[str, Any]) -> bool:
    block = approval_block(manifest)
    if not block:
        return False
    block_id = str(block.get("id"))
    return block_id in hook_targets(manifest) or "approval_workitem" in hook_targets(manifest)


def check_rbac_approve(manifest: dict[str, Any]) -> bool:
    rbac = manifest.get("rbac")
    return isinstance(rbac, dict) and bool(rbac.get("approve"))


def check_manager_email_field(manifest: dict[str, Any]) -> bool:
    for entity in entities(manifest):
        for field in entity.get("fields", []):
            if not isinstance(field, dict):
                continue
            name = str(field.get("name", "")).lower()
            if ("manager" in name or "approver" in name) and (
                    "mail" in name or str(field.get("format")) == "email"):
                return True
    return False


def check_notify_to_manager(manifest: dict[str, Any]) -> bool:
    fields = {
        str(field.get("name"))
        for entity in entities(manifest)
        for field in entity.get("fields", [])
        if isinstance(field, dict)
    }
    for block in blocks(manifest):
        if block.get("type") != "notify":
            continue
        params = block.get("params") if isinstance(block.get("params"), dict) else {}
        for recipient in params.get("to", []):
            if not isinstance(recipient, str) or not recipient.startswith("record."):
                continue
            field = recipient.removeprefix("record.")
            lowered = field.lower()
            if field in fields and ("manager" in lowered or "approver" in lowered) and "mail" in lowered:
                return True
    return False


def check_notify_hooked(manifest: dict[str, Any]) -> bool:
    targets = hook_targets(manifest)
    for block in blocks(manifest):
        if block.get("type") == "notify" and (
                str(block.get("id")) in targets or "notify" in targets):
            return True
    return False


def external_call_blocks(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    return [item for item in blocks(manifest) if item.get("type") == "external_call"]


def check_external_call_block(manifest: dict[str, Any]) -> bool:
    return bool(external_call_blocks(manifest))


def check_external_call_credential(manifest: dict[str, Any]) -> bool:
    for item in external_call_blocks(manifest):
        params = item.get("params") if isinstance(item.get("params"), dict) else {}
        if str(params.get("credentialRef", "")).strip():
            return True
    return False


def check_external_call_sink(manifest: dict[str, Any]) -> bool:
    for item in external_call_blocks(manifest):
        params = item.get("params") if isinstance(item.get("params"), dict) else {}
        sink = params.get("sink") if isinstance(params.get("sink"), dict) else {}
        mapping = sink.get("mapping") if isinstance(sink.get("mapping"), dict) else {}
        if mapping and all(isinstance(value, str) and value.startswith("/") for value in mapping.values()):
            return True
    return False


def check_external_call_schedule(manifest: dict[str, Any]) -> bool:
    for item in external_call_blocks(manifest):
        params = item.get("params") if isinstance(item.get("params"), dict) else {}
        schedule = params.get("schedule") if isinstance(params.get("schedule"), dict) else {}
        if schedule.get("every") and schedule.get("entity"):
            return True
    return False


def check_external_call_triggered(manifest: dict[str, Any]) -> bool:
    """An external_call must be scheduled or hooked, otherwise it never runs."""
    targets = hook_targets(manifest)
    for item in external_call_blocks(manifest):
        if str(item.get("id")) in targets:
            return True
        params = item.get("params") if isinstance(item.get("params"), dict) else {}
        if isinstance(params.get("schedule"), dict):
            return True
    return False


CHECKS = {
    "approval_block": check_approval_block,
    "approval_hooked": check_approval_hooked,
    "rbac_approve": check_rbac_approve,
    "manager_email_field": check_manager_email_field,
    "notify_to_manager": check_notify_to_manager,
    "notify_hooked": check_notify_hooked,
    "external_call_block": check_external_call_block,
    "external_call_credential": check_external_call_credential,
    "external_call_sink": check_external_call_sink,
    "external_call_schedule": check_external_call_schedule,
    "external_call_triggered": check_external_call_triggered,
}


def validate_schema(manifest: Any) -> bool:
    try:
        import jsonschema
    except ImportError:
        return True  # schema check skipped; structural checks still run
    schema = json.loads(BIZAPP_SCHEMA.read_text(encoding="utf-8"))
    try:
        jsonschema.validate(manifest, schema)
        return True
    except jsonschema.ValidationError:
        return False


def run_intent(model: str, base_url: str, cases: list[dict[str, Any]],
               timeout: int) -> list[CaseResult]:
    results = []
    for case in cases:
        output, latency = call_ollama(
            base_url, model, INTENT_SYSTEM, "User statement: " + case["prompt"],
            INTENT_SCHEMA, temperature=0.1, num_predict=512, timeout=timeout,
        )
        result = CaseResult(case["id"], model, "intent", latency, output=output)
        try:
            payload = parse_json(output)
            result.json_ok = True
            expected = case.get("expected", [])
            result.passed = ["intent"] if payload.get("intent") in expected else []
            result.failed = [] if result.passed else [f"expected {expected}, got {payload.get('intent')}"]
        except Exception as error:  # noqa: BLE001 - report the raw failure
            result.failed = [f"json: {error}"]
        results.append(result)
    return results


def run_draft(model: str, base_url: str, cases: list[dict[str, Any]],
              timeout: int) -> list[CaseResult]:
    system = extract_draft_system_prompt()
    results = []
    for case in cases:
        user = (
            "Create an application manifest for: Hint: " + case["prompt"]
            + "\n\nRecent conversation turns:\nuser: " + case["prompt"]
        )
        output, latency = call_ollama(
            base_url, model, system, user, json.loads(BIZAPP_SCHEMA.read_text(encoding="utf-8")),
            temperature=0.2, num_predict=2048, timeout=timeout,
        )
        result = CaseResult(case["id"], model, "draft", latency, output=output)
        try:
            manifest = parse_json(output)
            result.json_ok = True
            result.schema_ok = validate_schema(manifest)
            for check in case.get("checks", []):
                if CHECKS[check](manifest):
                    result.passed.append(check)
                else:
                    result.failed.append(check)
        except Exception as error:  # noqa: BLE001
            result.failed = [f"json: {error}"]
        results.append(result)
    return results


def summarize(results: list[CaseResult]) -> None:
    print("\nmodel                          task    case                            json  schema  checks        latency")
    print("-" * 118)
    for result in results:
        total = len(result.passed) + len(result.failed)
        checks = f"{len(result.passed)}/{total}" if total else "-"
        schema = "-" if result.schema_ok is None else ("ok" if result.schema_ok else "FAIL")
        print(f"{result.model:30} {result.task:7} {result.case_id:31} "
              f"{'ok' if result.json_ok else 'FAIL':4}  {schema:6}  {checks:12}  {result.latency_s:6.1f}s")
        for failure in result.failed:
            print(f"    ! {failure}")

    print("\naggregate")
    models = sorted({result.model for result in results})
    for model in models:
        intent = [r for r in results if r.model == model and r.task == "intent"]
        draft = [r for r in results if r.model == model and r.task == "draft"]
        parts = []
        if intent:
            passed = sum(1 for r in intent if r.passed)
            parts.append(f"intent {passed}/{len(intent)}")
        if draft:
            valid = sum(1 for r in draft if r.json_ok and r.schema_ok is not False)
            checks = sum(len(r.passed) for r in draft)
            total_checks = sum(len(r.passed) + len(r.failed) for r in draft)
            parts.append(f"draft schema {valid}/{len(draft)}")
            parts.append(f"checks {checks}/{total_checks}")
            parts.append(f"avg {sum(r.latency_s for r in draft) / len(draft):.1f}s")
        print(f"  {model}: " + ", ".join(parts))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", default="vylinh", help="comma-separated Ollama models")
    parser.add_argument("--base-url", default="http://127.0.0.1:11434")
    parser.add_argument("--only", choices=["intent", "draft"], help="run one task family")
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--out", help="write full results JSON here")
    parser.add_argument("--save-raw", help="directory for raw model outputs")
    args = parser.parse_args()

    cases = json.loads(CASES.read_text(encoding="utf-8"))
    models = [model.strip() for model in args.models.split(",") if model.strip()]
    raw_dir = pathlib.Path(args.save_raw) if args.save_raw else None
    if raw_dir:
        raw_dir.mkdir(parents=True, exist_ok=True)

    results: list[CaseResult] = []
    for model in models:
        if args.only in (None, "intent"):
            print(f"== {model}: intent ==")
            results.extend(run_intent(model, args.base_url, cases["intent"], args.timeout))
        if args.only in (None, "draft"):
            print(f"== {model}: draft ==")
            results.extend(run_draft(model, args.base_url, cases["draft"], args.timeout))

    if raw_dir:
        for result in results:
            (raw_dir / f"{result.model.replace(':', '_')}__{result.task}__{result.case_id}.txt").write_text(
                result.output, encoding="utf-8")

    summarize(results)
    if args.out:
        pathlib.Path(args.out).write_text(json.dumps([
            {
                "model": r.model,
                "task": r.task,
                "case": r.case_id,
                "latency_s": round(r.latency_s, 2),
                "json_ok": r.json_ok,
                "schema_ok": r.schema_ok,
                "passed": r.passed,
                "failed": r.failed,
            }
            for r in results
        ], ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nwrote {args.out}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
