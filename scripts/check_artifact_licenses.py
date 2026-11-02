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
"""Artifact license compliance check for OpenVie (Workstream L, §4(a)).

Verifies that every distributable artifact carries LICENSE + NOTICE:
1. Dockerfiles contain `COPY LICENSE NOTICE /licenses/`
2. Subproject directories (frontend, rag-chatbot-fastapi) have LICENSE + NOTICE
3. Compiled API classes / jar META-INF contains LICENSE and NOTICE
"""

from __future__ import annotations

from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    print("Checking artifact license compliance (§4(a))...")
    errors = []

    # 1. Root LICENSE and NOTICE
    if not (ROOT / "LICENSE").exists():
        errors.append("Root LICENSE is missing")
    if not (ROOT / "NOTICE").exists():
        errors.append("Root NOTICE is missing")

    # 2. Subproject LICENSE and NOTICE
    for sub in ["frontend", "rag-chatbot-fastapi"]:
        if not (ROOT / sub / "LICENSE").exists():
            errors.append(f"{sub}/LICENSE is missing")
        if not (ROOT / sub / "NOTICE").exists():
            errors.append(f"{sub}/NOTICE is missing")

    # 3. Dockerfiles check
    dockerfiles = [
        ROOT / "api/Dockerfile",
        ROOT / "frontend/Dockerfile",
        ROOT / "rag-chatbot-fastapi/Dockerfile",
    ]
    for df in dockerfiles:
        if not df.exists():
            errors.append(f"{df} does not exist")
            continue
        content = df.read_text(encoding="utf-8")
        if "COPY LICENSE NOTICE /licenses/" not in content:
            errors.append(f"{df.relative_to(ROOT)} missing 'COPY LICENSE NOTICE /licenses/'")

    # 4. Java classes / JAR META-INF check
    meta_inf = ROOT / "api/target/classes/META-INF"
    if meta_inf.exists():
        if not (meta_inf / "LICENSE").exists():
            errors.append("api/target/classes/META-INF/LICENSE is missing")
        if not (meta_inf / "NOTICE").exists():
            errors.append("api/target/classes/META-INF/NOTICE is missing")

    jar_path = ROOT / "api/target/openvie-api.jar"
    if jar_path.exists():
        with zipfile.ZipFile(jar_path, "r") as zf:
            namelist = zf.namelist()
            if not any(n.endswith("META-INF/LICENSE") for n in namelist):
                errors.append("JAR missing META-INF/LICENSE")
            if not any(n.endswith("META-INF/NOTICE") for n in namelist):
                errors.append("JAR missing META-INF/NOTICE")

    if errors:
        print(f"FAILED: {len(errors)} artifact license issues found:")
        for err in errors:
            print("  -", err)
        return 1

    print("All distributable artifacts comply with §4(a) LICENSE + NOTICE distribution.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
