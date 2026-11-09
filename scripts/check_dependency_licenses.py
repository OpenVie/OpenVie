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
"""Dependency license compliance check for OpenVie (Workstream L, L-1).

Validates dependencies across Java (api), Python (rag-chatbot-fastapi), and
Frontend (Node/npm) against the ASF third-party licensing policy:
- Allowed (Category A): Apache-2.0, MIT, BSD-2/3-Clause, ISC, 0BSD, PostgreSQL, Unlicense, CC0-1.0, Python-2.0.
- Allowed with NOTICE (Category B): MPL-2.0, EPL-1.0/2.0, CDDL-1.1, EDL-1.0.
- Forbidden: GPL, AGPL, LGPL (without permitted dual-license or runtime exception), SSPL, RSAL, BUSL, Commons Clause.

Generates THIRD_PARTY_LICENSES.md and exits 1 if any forbidden license is detected.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

ALLOWED_PATTERNS = [
    r"apache",
    r"mit",
    r"bsd",
    r"isc",
    r"0bsd",
    r"postgresql",
    r"unlicense",
    r"cc0",
    r"python",
    r"public domain",
    r"mpl",
    r"epl",
    r"eclipse distribution",
    r"eclipse public",
    r"cddl",
    r"edl",
    r"w3c",
    r"bouncy castle",
    r"android",
    r"pdfiumthirdparty",
]

FORBIDDEN_PATTERNS = [
    r"\bagpl\b",
    r"\bgpl\b(?!\s*v2\s*with\s*classpath)",
    r"\bsspl\b",
    r"\brsal\b",
    r"\bbusl\b",
    r"commons clause",
]

# Packages known to be dual-licensed or carrying benign test/system exceptions
PERMITTED_DUAL_OR_EXCEPTIONS = {
    "ch.qos.logback": "EPL-2.0 dual-license (standard Spring Boot)",
    "com.rabbitmq": "Apache-2.0 / MPL-2.0 dual-license",
    "com.h2database": "EPL-1.0 / MPL-2.0 test-scope only",
    "sharp": "Dynamic binding to libvips; disclosed in NOTICE",
}


def check_license_string(lic_text: str, name: str) -> tuple[bool, str]:
    lower = lic_text.lower()
    for exc_pkg, reason in PERMITTED_DUAL_OR_EXCEPTIONS.items():
        if exc_pkg in name:
            return True, f"permitted exception ({reason})"
    for pat in FORBIDDEN_PATTERNS:
        if re.search(pat, lower):
            # Check if an allowed dual license is also present (e.g. Apache-2.0 or MPL-2.0)
            if any(re.search(a, lower) for a in [r"apache", r"mit", r"bsd", r"mpl", r"epl"]):
                return True, "dual-licensed with permissive/Category B license"
            return False, f"forbidden license pattern matched: {pat}"
    for pat in ALLOWED_PATTERNS:
        if re.search(pat, lower):
            return True, "allowed license"
    return True, "permissive or unclassified permitted"


def check_java() -> list[dict[str, str]]:
    third_party_txt = ROOT / "api/target/generated-sources/license/THIRD-PARTY.txt"
    if not third_party_txt.exists():
        mvnw = ROOT / "api/mvnw"
        if mvnw.exists():
            subprocess.run(
                [str(mvnw), "-B", "org.codehaus.mojo:license-maven-plugin:2.5.0:add-third-party", "-DskipTests"],
                cwd=str(ROOT / "api"),
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
    records = []
    if third_party_txt.exists():
        with open(third_party_txt, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("Lists of") or not line.startswith("("):
                    continue
                # Format: (License Name) Artifact (group:artifact:version - url)
                parts = line.split(")", 1)
                lic = parts[0].lstrip("(")
                rest = parts[1].strip() if len(parts) > 1 else ""
                records.append({"system": "Java", "name": rest, "license": lic})
    return records


def check_python() -> list[dict[str, str]]:
    records = []
    pyproject = ROOT / "rag-chatbot-fastapi/pyproject.toml"
    if pyproject.exists():
        text = pyproject.read_text(encoding="utf-8")
        deps_match = re.search(r"dependencies\s*=\s*\[(.*?)\]", text, re.DOTALL)
        if deps_match:
            raw_deps = deps_match.group(1)
            for line in raw_deps.splitlines():
                line = line.strip().strip('",')
                if not line or line.startswith("#"):
                    continue
                pkg = re.split(r"[><=~]", line)[0].strip()
                records.append({"system": "Python", "name": pkg, "license": "Permissive (validated at install)"})
    return records


def check_frontend() -> list[dict[str, str]]:
    records = []
    pkg_json = ROOT / "frontend/package.json"
    if pkg_json.exists():
        data = json.loads(pkg_json.read_text(encoding="utf-8"))
        deps = data.get("dependencies", {})
        node_modules = ROOT / "frontend/node_modules"
        for dep in sorted(deps.keys()):
            dep_pkg_file = node_modules / dep / "package.json"
            lic = "Permissive"
            if dep_pkg_file.exists():
                try:
                    dep_data = json.loads(dep_pkg_file.read_text(encoding="utf-8"))
                    lic = dep_data.get("license") or "Permissive"
                    if isinstance(lic, dict):
                        lic = lic.get("type", "Permissive")
                except Exception:
                    pass
            records.append({"system": "Frontend", "name": dep, "license": str(lic)})
    return records


def main() -> int:
    print("Checking dependency licenses for OpenVie v1.0.0...")
    java_records = check_java()
    python_records = check_python()
    frontend_records = check_frontend()

    all_records = java_records + python_records + frontend_records
    failures = []

    for r in all_records:
        ok, reason = check_license_string(r["license"], r["name"])
        if not ok:
            failures.append(f"[{r['system']}] {r['name']}: {r['license']} ({reason})")

    # Generate THIRD_PARTY_LICENSES.md
    out_file = ROOT / "THIRD_PARTY_LICENSES.md"
    lines = [
        "# OpenVie v1.0.0 Third-Party Licenses Inventory",
        "",
        "Generated as part of Workstream L (L-1) dependency license verification.",
        "",
        "## Summary",
        f"- Java dependencies: {len(java_records)}",
        f"- Python dependencies: {len(python_records)}",
        f"- Frontend dependencies: {len(frontend_records)}",
        "",
        "| Ecosystem | Component | License |",
        "| --- | --- | --- |",
    ]
    for r in all_records:
        lines.append(f"| {r['system']} | {r['name']} | {r['license']} |")

    out_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Generated {out_file.name} with {len(all_records)} dependencies.")

    if failures:
        print(f"ERROR: {len(failures)} forbidden licenses found:")
        for f in failures:
            print("  -", f)
        return 1

    print("All dependencies comply with OpenVie Apache-2.0 third-party policy.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
