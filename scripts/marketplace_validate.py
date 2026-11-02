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
"""CI gate for platform-bundled BizApp manifests (Tier 3).

Validates every JSON in the marketplace directory against the bizapp.v1
contract independently of the JVM: the authoritative gate is the Java
BizAppManifestValidator (enforced at install time and in test by
BizAppBundledTemplatesTest); this script catches regressions earlier in CI
with a second, deliberately simple implementation.

Usage: python3 scripts/marketplace_validate.py [--dir PATH]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SCHEMA = "bizapp.v1"
KIND = re.compile(r"[a-z][a-z0-9-]{1,31}")
NAME = re.compile(r"[A-Za-z][A-Za-z0-9_]{0,63}")
EVENT = re.compile(r"[a-z0-9][a-z0-9._-]{1,62}")
BPMN = re.compile(r"processes/[a-z0-9][a-z0-9-]*\.bpmn20\.xml")
NAMED_PARAM = re.compile(r":[A-Za-z][A-Za-z0-9_]*")

BLOCK_TYPES = {
    "approval_workitem", "assistant", "knowledge_base", "report",
    "notify", "webhook_out", "external_call", "registry", "form", "timeline",
}
FIELD_TYPES = {"string", "number", "boolean", "enum", "date"}
ROLES = {"ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER"}
RBAC_KEYS = {"read", "write", "delete", "approve"}
HOOK_KEYS = {"onCreate", "onUpdate", "onDecide"}
VIEW_PREFIXES = {"registry", "form", "assistant", "report", "timeline"}
FORBIDDEN_SQL_TOKENS = {
    "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE",
    "GRANT", "REVOKE", "PRAGMA", "CALL", "COPY", "MERGE", "VACUUM",
    "ANALYZE", "EXPLAIN", "ATTACH", "DETACH", "SET", "USE", "UNION",
}


class Invalid(Exception):
    """One manifest rule violation."""


def fail(path: Path, message: str) -> Invalid:
    return Invalid(f"{path}: {message}")


def require(condition: bool, path: Path, message: str) -> None:
    if not condition:
        raise fail(path, message)


def check_select_only(path: Path, sql: str) -> None:
    normalized = " ".join(str(sql).split()).strip()
    upper = normalized.upper()
    require(upper.startswith("SELECT "), path, "report sql must start with SELECT")
    require(";" not in upper, path, "report sql must be a single statement")
    require("${" not in normalized, path, "report sql must not interpolate")
    words = {w for w in upper.split() if w}
    banned = words & FORBIDDEN_SQL_TOKENS
    require(not banned, path, f"forbidden SQL keyword(s): {sorted(banned)}")
    follows = re.split(r"[()\s,]+", upper)
    banned_between = {w for w in follows if w in FORBIDDEN_SQL_TOKENS}
    require(not banned_between, path, f"forbidden SQL token(s): {sorted(banned_between)}")
    params = set(NAMED_PARAM.findall(normalized))
    require(params <= {":tenant", ":app"},
            path, f"only :tenant/:app binds allowed, found {sorted(params)}")


def check_block(path: Path, block: dict, declared_events: set[str]) -> None:
    require(isinstance(block, dict), path, "block must be an object")
    require(bool(NAME.match(block.get("id", ""))), path, "bad block id")
    btype = block.get("type")
    require(btype in BLOCK_TYPES, path, f"unknown block type {btype!r}")
    params = block.get("params") or {}
    if btype == "approval_workitem":
        bpmn = params.get("bpmnTemplate")
        require(isinstance(bpmn, str) and BPMN.match(bpmn),
                path, "approval_workitem bpmnTemplate must match processes/<n>.bpmn20.xml")
        role = params.get("candidateRole")
        require(role is None or role in ROLES, path, "unsupported candidateRole")
    elif btype == "report":
        views = params.get("views")
        require(isinstance(views, list) and views, path, "report needs views")
        for view in views:
            require(isinstance(view, dict) and NAME.match(view.get("name", ""))
                    and isinstance(view.get("sql"), str),
                    path, "report view needs name + sql")
            check_select_only(path, view["sql"])
    elif btype == "external_call":
        require(bool(params.get("url")), path, "external_call needs url")
        require(bool(params.get("credentialRef")),
                path, "external_call secrets must use credentialRef")
        lowered = {str(k).lower() for k in params}
        require(not any(k.endswith(("password", "secret", "token", "apikey", "api_key"))
                        or k == "token" for k in lowered),
                path, "inline secret-shaped field rejected; use credentialRef")
    elif btype == "notify":
        to = params.get("to")
        require(isinstance(to, list) and to, path, "notify needs a `to` list")
    elif btype == "webhook_out":
        events = params.get("events")
        require(isinstance(events, list) and events, path, "webhook_out needs events")
        for event in events:
            require(isinstance(event, str) and EVENT.match(event),
                    path, f"bad webhook event kind {event!r}")
            declared_events.add(event)


def check_manifest(path: Path, manifest: dict) -> None:
    require(manifest.get("schema") == SCHEMA, path, "schema must be " + SCHEMA)
    require(bool(KIND.match(manifest.get("kind", ""))), path, "bad kind")
    name = manifest.get("name") or {}
    require(bool(str(name.get("vi") or "").strip()) and bool(str(name.get("en") or "").strip()),
            path, "name.vi and name.en are required")
    require(isinstance(manifest.get("version", 1), int) and manifest.get("version", 1) >= 1,
            path, "version must be an int >= 1")
    require(manifest.get("entities"), path, "entities must not be empty")

    for key, roles in (manifest.get("rbac") or {}).items():
        require(key in RBAC_KEYS, path, f"unknown rbac capability {key!r}")
        require(bool(roles) and all(r in ROLES for r in roles), path, f"rbac.{key} has unknown roles")

    block_ids: set[str] = set()
    declared_events: set[str] = set()
    for block in manifest.get("blocks") or []:
        check_block(path, block, declared_events)
        block_ids.add(block["id"])

    for entity in manifest.get("entities") or []:
        require(NAME.match(entity.get("name", "")), path, "bad entity name")
        fields = entity.get("fields")
        require(fields, path, f"entity {entity.get('name')} needs fields")
        seen: set[str] = set()
        for field in fields:
            fname = field.get("name", "")
            require(bool(NAME.match(fname)), path, "bad field name")
            require(fname not in seen, path, f"duplicate field {fname}")
            seen.add(fname)
            require(field.get("type") in FIELD_TYPES,
                    path, f"field {fname} has unsupported type {field.get('type')!r}")
            if field.get("type") == "enum":
                require(field.get("options"), path, f"enum field {fname} needs options")
            if field.get("maxLen") is not None:
                require(1 <= int(field["maxLen"]) <= 1024, path, f"field {fname} maxLen out of range")
        for hook, targets in (entity.get("hooks") or {}).items():
            require(hook in HOOK_KEYS, path, f"unknown hook {hook!r}")
            for target in targets or []:
                if target.startswith("webhook_out:"):
                    require(target.split(":", 1)[1] in declared_events,
                            path, f"hook target {target} has no webhook_out declaring it")
                else:
                    require(target in block_ids, path, f"hook target missing block {target!r}")

    entity_names = {entity["name"] for entity in manifest["entities"] if isinstance(entity, dict) and entity.get("name")}
    report_views = set()
    for block in manifest.get("blocks") or []:
        if block.get("type") == "report":
            for view in (block.get("params") or {}).get("views") or []:
                if isinstance(view, dict) and view.get("name"):
                    report_views.add(view["name"])
    for view in (manifest.get("ui") or {}).get("views") or []:
        prefix, _, target = str(view).partition(":")
        require(prefix in VIEW_PREFIXES and target, path, f"bad ui view {view!r}")
        if prefix == "assistant":
            require(target in block_ids, path, f"ui view {view!r} references a missing block")
        elif prefix == "report":
            require(target in report_views, path, f"ui view {view!r} references a missing report view")
        else:
            require(target in entity_names, path, f"ui view {view!r} references a missing entity")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dir", default="api/src/main/resources/marketplace/bizapps",
                        help="marketplace manifest directory")
    args = parser.parse_args()

    directory = Path(args.dir)
    files = sorted(directory.glob("*.json"))
    if len(files) < 2:
        print(f"ERROR: expected bundled templates in {directory}, found {len(files)}", file=sys.stderr)
        return 1

    errors: list[str] = []
    for path in files:
        try:
            with path.open(encoding="utf-8") as handle:
                manifest = json.load(handle)
            check_manifest(path, manifest)
        except (json.JSONDecodeError, Invalid) as error:
            errors.append(str(error))
        except Exception as error:  # noqa: BLE001 - CI gate reports any failure
            errors.append(f"{path}: unreadable ({error})")

    if errors:
        print("marketplace-validate FAILED:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    kinds = sorted(Path(p).stem for p in files)
    print(f"marketplace-validate OK ({len(files)} templates: {', '.join(kinds)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
