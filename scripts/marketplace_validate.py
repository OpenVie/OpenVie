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
"""CI gate for platform-bundled BizApp manifests and templates (Tier 3).

Enforces:
- openvie.template.v1 envelope + meta (SPDX license, author, sha256 match, generator)
- bizapp.v1 declarative reports (NO SQL string permitted anywhere)
- ref field target entity validation
- knowledge projection fail-closed RBAC rule
- vendor name denylist (trademark clause §6)
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

SCHEMA = "bizapp.v1"
ENVELOPE = "openvie.template.v1"

KIND = re.compile(r"^[a-z][a-z0-9-]{1,31}$")
NAME = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,63}$")
IDENT = re.compile(r"^[a-z][a-z0-9_]{0,63}$")
EVENT = re.compile(r"^[a-z0-9][a-z0-9._-]{1,62}$")
BPMN = re.compile(r"^processes/[a-z0-9][a-z0-9-]*\.bpmn20\.xml$")

BLOCK_TYPES = {
    "approval_workitem", "assistant", "knowledge_base", "report",
    "notify", "webhook_out", "external_call",
    "registry", "form", "timeline",
}
FIELD_TYPES = {"string", "number", "boolean", "enum", "date", "ref"}
ROLES = {"ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER"}
RBAC_KEYS = {"read", "write", "delete", "approve"}
HOOK_KEYS = {"onCreate", "onUpdate", "onDecide"}
VIEW_PREFIXES = {"registry", "form", "assistant", "report", "timeline"}
ALLOWED_METRICS = {"count", "sum", "avg", "min", "max"}
ALLOWED_OPS = {"eq", "ne", "in", "gt", "gte", "lt", "lte", "contains", "isnull"}

ALLOWED_SPDX = {"Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "CC0-1.0", "CC-BY-4.0"}

# §6 Trademark denylist for vendor product names
VENDOR_DENYLIST = {
    "docusign", "adobesign", "adobe", "hellosign", "pandadoc", "signnow",
    "flowise", "salesforce", "hubspot", "jira", "zendesk", "zoho",
}


class Invalid(Exception):
    """One manifest or envelope rule violation."""


def fail(path: Path, message: str) -> Invalid:
    return Invalid(f"{path}: {message}")


def require(condition: bool, path: Path, message: str) -> None:
    if not condition:
        raise fail(path, message)


def check_envelope(path: Path, doc: dict) -> dict:
    if doc.get("envelope") == ENVELOPE:
        meta = doc.get("meta")
        require(isinstance(meta, dict), path, "template envelope missing meta object")
        require(bool(meta.get("author")), path, "meta.author required")
        require(meta.get("license") in ALLOWED_SPDX, path, f"meta.license must be one of {sorted(ALLOWED_SPDX)}")
        require(meta.get("generator") in {"human", "ai"}, path, "meta.generator must be 'human' or 'ai'")

        manifest = doc.get("manifest")
        require(isinstance(manifest, dict), path, "template envelope missing manifest object")

        expected_sha = meta.get("sha256")
        if expected_sha:
            actual_sha = hashlib.sha256(json.dumps(manifest, separators=(',', ':'), sort_keys=True).encode()).hexdigest()
            require(expected_sha == actual_sha, path, f"meta.sha256 mismatch: expected {expected_sha}, computed {actual_sha}")
        return manifest
    return doc


def check_block(path: Path, block: dict, entity_fields: dict[str, dict[str, dict]], declared_events: set[str]) -> None:
    require(isinstance(block, dict), path, "block must be an object")
    require(bool(NAME.match(block.get("id", ""))), path, "bad block id")
    btype = block.get("type")
    require(btype in BLOCK_TYPES, path, f"unknown block type {btype!r}")
    params = block.get("params") or {}

    if btype == "approval_workitem":
        bpmn = params.get("bpmnTemplate")
        require(isinstance(bpmn, str) and bool(BPMN.match(bpmn)),
                path, "approval_workitem bpmnTemplate must match processes/<n>.bpmn20.xml")
        role = params.get("candidateRole")
        require(role is None or role in ROLES, path, "unsupported candidateRole")
    elif btype == "report":
        views = params.get("views")
        require(isinstance(views, list) and views, path, "report needs views")
        for view in views:
            require(isinstance(view, dict), path, "report view must be an object")
            require("sql" not in view, path, "raw sql is forbidden in report views; use declarative views")
            vname = view.get("name")
            require(isinstance(vname, str) and bool(IDENT.match(vname)), path, f"invalid report view name {vname!r}")
            ventity = view.get("entity")
            require(ventity in entity_fields, path, f"report view references unknown entity {ventity!r}")

            fields = entity_fields[ventity]
            view_aliases = set()

            for gb in view.get("groupBy") or []:
                require(gb in fields, path, f"report view {vname} groupBy field {gb!r} not found in entity {ventity}")
                view_aliases.add(gb)

            for m in view.get("metrics") or []:
                fn = str(m.get("fn", "")).lower()
                require(fn in ALLOWED_METRICS, path, f"unsupported metric fn {fn!r}")
                as_name = m.get("as")
                require(isinstance(as_name, str) and bool(IDENT.match(as_name)), path, f"invalid metric as alias {as_name!r}")
                view_aliases.add(as_name)
                mfield = m.get("field")
                if fn in {"sum", "avg"}:
                    require(mfield in fields and fields[mfield].get("type") == "number",
                            path, f"metric {fn} requires numeric field, got {mfield!r}")
                elif mfield:
                    require(mfield in fields, path, f"metric field {mfield!r} not found in entity {ventity}")

            for f in view.get("filters") or []:
                ffield = f.get("field")
                require(ffield in fields, path, f"filter field {ffield!r} not found in entity {ventity}")
                op = str(f.get("op", "")).lower()
                require(op in ALLOWED_OPS, path, f"unsupported filter op {op!r}")

            for ob in view.get("orderBy") or []:
                by = ob.get("by")
                require(by in view_aliases or by in fields, path, f"orderBy {by!r} must match an entity field or metric alias")
                if "dir" in ob:
                    require(str(ob["dir"]).lower() in {"asc", "desc"}, path, "orderBy dir must be 'asc' or 'desc'")

            if "limit" in view:
                require(isinstance(view["limit"], int) and 1 <= view["limit"] <= 500, path, "report limit must be 1..500")
            if "engine" in view:
                require(view["engine"] in {"postgres", "lakehouse"}, path, "report engine must be 'postgres' or 'lakehouse'")

    elif btype == "external_call":
        require(bool(params.get("url")), path, "external_call needs url")
        require(bool(params.get("credentialRef")), path, "external_call secrets must use credentialRef")
        lowered = {str(k).lower() for k in params}
        require(not any(k.endswith(("password", "secret", "token", "apikey", "api_key")) for k in lowered),
                path, "inline secret-shaped field rejected; use credentialRef")
    elif btype == "notify":
        to = params.get("to")
        require(isinstance(to, list) and to, path, "notify needs a `to` list")
    elif btype == "webhook_out":
        events = params.get("events")
        require(isinstance(events, list) and events, path, "webhook_out needs events")
        for event in events:
            require(isinstance(event, str) and bool(EVENT.match(event)), path, f"bad webhook event kind {event!r}")
            declared_events.add(event)


def check_manifest(path: Path, manifest: dict, is_bundled: bool = True) -> None:
    require(manifest.get("schema") == SCHEMA, path, "schema must be " + SCHEMA)
    kind = manifest.get("kind", "")
    require(bool(KIND.match(kind)), path, "bad kind")

    # Trademark check §6
    for vendor in VENDOR_DENYLIST:
        require(vendor not in kind.lower(), path, f"kind contains forbidden vendor name: {vendor}")
        name = manifest.get("name") or {}
        for lang in ("vi", "en"):
            require(vendor not in str(name.get(lang) or "").lower(), path, f"name.{lang} contains forbidden vendor name: {vendor}")

    name = manifest.get("name") or {}
    require(bool(str(name.get("vi") or "").strip()) and bool(str(name.get("en") or "").strip()),
            path, "name.vi and name.en are required")
    require(isinstance(manifest.get("version", 1), int) and manifest.get("version", 1) >= 1,
            path, "version must be an int >= 1")

    if manifest.get("storage") == "typed":
        require(is_bundled, path, "storage: typed is permitted only for platform-bundled templates")

    entities = manifest.get("entities") or []
    require(entities, path, "entities must not be empty")
    require(len(entities) <= 20, path, "max 20 entities per manifest")

    rbac = manifest.get("rbac") or {}
    for key, roles in rbac.items():
        require(key in RBAC_KEYS, path, f"unknown rbac capability {key!r}")
        require(bool(roles) and all(r in ROLES for r in roles), path, f"rbac.{key} has unknown roles")

    read_roles = set(rbac.get("read") or [])
    has_all_read_roles = ROLES.issubset(read_roles)
    entity_fields: dict[str, dict[str, dict]] = {}
    for entity in entities:
        ename = entity.get("name", "")
        require(bool(IDENT.match(ename)), path, f"bad entity name {ename!r}")
        fields = entity.get("fields") or []
        require(fields, path, f"entity {ename} needs fields")
        require(len(fields) <= 40, path, f"entity {ename} exceeds max 40 fields")
        fmap = {}
        for field in fields:
            fname = field.get("name", "")
            require(bool(NAME.match(fname)), path, f"bad field name {fname!r}")
            require(fname not in fmap, path, f"duplicate field {fname!r} on entity {ename}")
            ftype = field.get("type")
            require(ftype in FIELD_TYPES, path, f"unsupported field type {ftype!r} for {fname}")
            if ftype == "enum":
                require(bool(field.get("options")), path, f"enum field {fname} requires options")
            fmap[fname] = field
        entity_fields[ename] = fmap

        # Knowledge projection fail-closed check (D)
        knowledge = entity.get("knowledge")
        if knowledge and knowledge.get("project") is True:
            require(has_all_read_roles, path,
                    f"entity {ename} knowledge.project requires rbac.read to include all workspace roles")
            title_field = knowledge.get("titleField")
            if title_field:
                require(title_field in fmap, path, f"knowledge.titleField {title_field!r} not in entity {ename}")
            for kf in knowledge.get("fields") or []:
                require(kf in fmap, path, f"knowledge.fields contains undeclared field {kf!r}")
                require(not fmap[kf].get("pii"), path, f"pii field {kf!r} cannot be projected")

    # ref field target entity check
    for ename, fmap in entity_fields.items():
        for fname, field in fmap.items():
            if field.get("type") == "ref":
                ref_entity = field.get("entity")
                require(ref_entity in entity_fields, path,
                        f"ref field {fname} on {ename} references undeclared entity {ref_entity!r}")

    blocks = manifest.get("blocks") or []
    require(len(blocks) <= 40, path, "max 40 blocks per manifest")
    block_ids: set[str] = set()
    declared_events: set[str] = set()
    for block in blocks:
        check_block(path, block, entity_fields, declared_events)
        block_ids.add(block["id"])

    # UI check
    ui = manifest.get("ui") or {}
    views = ui.get("views") or []
    require(len(views) <= 20, path, "max 20 ui views")
    for view in views:
        parts = view.split(":", 1)
        require(len(parts) == 2 and parts[0] in VIEW_PREFIXES and parts[1].strip(),
                path, f"invalid ui view {view!r}")


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
                raw_doc = json.load(handle)
            manifest = check_envelope(path, raw_doc)
            check_manifest(path, manifest, is_bundled=True)
        except (json.JSONDecodeError, Invalid) as error:
            errors.append(str(error))
        except Exception as error:
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
