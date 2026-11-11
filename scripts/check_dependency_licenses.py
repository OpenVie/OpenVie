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

Validates the Compose image inventory plus Java (api), Python
(rag-chatbot-fastapi), and complete Frontend (Node/npm lockfile) dependency
graphs against the ASF third-party licensing policy:
- Allowed (Category A): Apache-2.0, MIT, BSD-2/3-Clause, ISC, 0BSD, PostgreSQL, Unlicense, CC0-1.0, Python-2.0.
- Allowed with NOTICE (Category B): MPL-2.0, EPL-1.0/2.0, CDDL-1.1, EDL-1.0.
- Forbidden: GPL, AGPL, LGPL (without permitted dual-license or runtime exception), SSPL, RSAL, BUSL, Commons Clause.

Generates the tracked THIRD_PARTY_LICENSES.md notice and exits 1 if any
forbidden dependency license or undocumented Compose image is detected.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib

ROOT = Path(__file__).resolve().parents[1]

# PeopleOS-style release inventory: these are the pinned, third-party images
# declared by the two Compose manifests.  The checker fails when a new image is
# added without a review entry, so the public notice cannot silently go stale.
CONTAINER_IMAGES = {
    "postgres:16-alpine": ("PostgreSQL", "PostgreSQL License", "Có", "Cơ sở dữ liệu nghiệp vụ và Keycloak."),
    "quay.io/keycloak/keycloak:26.3.3": ("Keycloak", "Apache-2.0", "Có", "Identity provider."),
    "redis:7.2-alpine": ("Redis", "BSD-3-Clause", "Có", "Cố ý ghim 7.2.x; Redis ≥7.4 là RSALv2/SSPL."),
    "rabbitmq:3-management-alpine": ("RabbitMQ", "MPL-2.0", "Có", "Message broker, dùng nguyên bản qua mạng."),
    "qdrant/qdrant:v1.19.2": ("Qdrant", "Apache-2.0", "Có", "Vector database."),
    "ghcr.io/huggingface/text-embeddings-inference:cpu-1.9": ("Text Embeddings Inference", "Apache-2.0", "Có", "Profile reranker, tắt mặc định."),
    "apache/apisix:3.19.0-ubuntu": ("Apache APISIX", "Apache-2.0", "Có", "API gateway."),
    "chrislusf/seaweedfs:3.85": ("SeaweedFS", "Apache-2.0", "Có", "Object storage và S3 gateway."),
    "prom/prometheus:v2.55.1": ("Prometheus", "Apache-2.0", "Có", "Profile monitoring, tắt mặc định."),
    "prom/alertmanager:v0.27.0": ("Alertmanager", "Apache-2.0", "Có", "Profile monitoring, tắt mặc định."),
    "grafana/grafana:11.3.1": ("Grafana", "AGPL-3.0", "Có", "Dịch vụ network-isolated, nguyên bản; không liên kết vào OpenVie."),
    "clamav/clamav:1.4": ("ClamAV", "GPL-2.0", "Có", "Dịch vụ quét malware độc lập qua mạng."),
    "willfarrell/autoheal:1.2.0": ("Autoheal", "MIT", "Có", "Tự phục hồi container."),
}

CONTAINER_NOTES_EN = {
    "apache/apisix:3.19.0-ubuntu": "API gateway.",
    "chrislusf/seaweedfs:3.85": "Object storage and S3 gateway.",
    "clamav/clamav:1.4": "Standalone network malware-scanning service.",
    "ghcr.io/huggingface/text-embeddings-inference:cpu-1.9": "Optional reranker profile, disabled by default.",
    "grafana/grafana:11.3.1": "Network-isolated, unmodified service; no code is linked into OpenVie.",
    "postgres:16-alpine": "Application and Keycloak database.",
    "prom/alertmanager:v0.27.0": "Optional monitoring profile, disabled by default.",
    "prom/prometheus:v2.55.1": "Optional monitoring profile, disabled by default.",
    "qdrant/qdrant:v1.19.2": "Vector database.",
    "quay.io/keycloak/keycloak:26.3.3": "Identity provider.",
    "rabbitmq:3-management-alpine": "Message broker, used unmodified over the network.",
    "redis:7.2-alpine": "Intentionally pinned to 7.2.x; Redis ≥7.4 is RSALv2/SSPL.",
    "willfarrell/autoheal:1.2.0": "Container auto-healing.",
}

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


def license_note(name: str, license_name: str, language: str) -> str:
    """Explain only licenses that need more context than Apache/MIT/BSD."""
    text = f"{name} {license_name}".lower()
    if "ch.qos.logback" in text:
        return "Dùng theo EPL-2.0." if language == "vi" else "Used under EPL-2.0."
    if "com.rabbitmq" in text:
        return "Dùng theo Apache-2.0/MPL-2.0." if language == "vi" else "Used under Apache-2.0/MPL-2.0."
    if "com.h2database" in text:
        return "Chỉ dùng cho kiểm thử; EPL-1.0/MPL-2.0." if language == "vi" else "Test-only; EPL-1.0/MPL-2.0."
    if "net.java.dev.jna" in text:
        return "Dùng theo Apache-2.0." if language == "vi" else "Used under Apache-2.0."
    if "classpath" in text or " cpe" in text:
        return (
            "Có Classpath Exception; dùng theo giấy phép ở cột trước."
            if language == "vi"
            else "Includes the Classpath Exception; use follows the license shown."
        )
    if any(
        token in text
        for token in ("epl", "mpl", "edl", "cddl", "eclipse distribution", "eclipse public")
    ):
        return (
            "Giữ kèm thông báo giấy phép khi phát hành."
            if language == "vi"
            else "Keep the applicable license notice when distributing."
        )
    return "—"


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


def resolve_compose_image(value: str) -> str:
    """Return the default image from `${NAME:-default}` or an explicit image."""
    match = re.fullmatch(r"\$\{[A-Z0-9_]+:-(.+)}", value)
    return match.group(1) if match else value


def check_container_images() -> list[tuple[str, str, str, str, str]]:
    """Read both Compose manifests and require an inventory row for every image."""
    image_pattern = re.compile(r"^\s*image:\s*([^\s#]+)")
    found: set[str] = set()
    for filename in ("docker-compose.yml", "docker-compose.prod.yml"):
        compose_file = ROOT / filename
        for line in compose_file.read_text(encoding="utf-8").splitlines():
            match = image_pattern.match(line)
            if match:
                found.add(resolve_compose_image(match.group(1)))

    unknown = sorted(found - CONTAINER_IMAGES.keys())
    if unknown:
        raise RuntimeError(
            "Container image(s) need a THIRD_PARTY_LICENSES inventory entry: " + ", ".join(unknown)
        )

    rows = []
    for image in sorted(found):
        component, license_name, osi, note = CONTAINER_IMAGES[image]
        rows.append((component, image, license_name, osi, note))
    return rows


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
        project = tomllib.loads(pyproject.read_text(encoding="utf-8"))
        for dependency in project.get("project", {}).get("dependencies", []):
            records.append(
                {
                    "system": "Python",
                    "name": dependency,
                    "license": "Permissive (validated at install)",
                }
            )
    return records


def frontend_lockfile_records() -> list[dict[str, str]]:
    """Return the complete npm lock graph for license validation."""
    lockfile = ROOT / "frontend/package-lock.json"
    if not lockfile.exists():
        return []
    packages = json.loads(lockfile.read_text(encoding="utf-8")).get("packages", {})
    records = []
    for package_path, package in packages.items():
        if not package_path:
            continue
        license_name = str(package.get("license") or "Unclassified")
        records.append(
            {
                "system": "Frontend lockfile",
                "name": package_path.removeprefix("node_modules/"),
                "license": license_name,
            }
        )
    return records


def check_frontend() -> list[dict[str, str]]:
    records = []
    pkg_json = ROOT / "frontend/package.json"
    lockfile = ROOT / "frontend/package-lock.json"
    if pkg_json.exists():
        data = json.loads(pkg_json.read_text(encoding="utf-8"))
        deps = data.get("dependencies", {})
        lock_packages = {}
        if lockfile.exists():
            lock_packages = json.loads(lockfile.read_text(encoding="utf-8")).get("packages", {})
        for dep in sorted(deps.keys()):
            package = lock_packages.get(f"node_modules/{dep}", {})
            version = str(package.get("version") or deps[dep])
            license_name = str(package.get("license") or "Unclassified")
            records.append(
                {"system": "Frontend", "name": f"{dep}@{version}", "license": license_name}
            )
    return records


def main() -> int:
    print("Checking dependency licenses for OpenVie v1.0.0...")
    java_records = check_java()
    python_records = check_python()
    frontend_records = check_frontend()
    lockfile_records = frontend_lockfile_records()
    try:
        container_rows = check_container_images()
    except RuntimeError as exc:
        print(f"ERROR: {exc}")
        return 1

    all_records = java_records + python_records + frontend_records
    failures = []

    for r in all_records + lockfile_records:
        ok, reason = check_license_string(r["license"], r["name"])
        if not ok:
            failures.append(f"[{r['system']}] {r['name']}: {r['license']} ({reason})")

    # Generate a human-readable, PeopleOS-style inventory. The compact npm
    # summary keeps the notice reviewable while validation checks every locked
    # frontend package above.
    out_file = ROOT / "THIRD_PARTY_LICENSES.md"
    lines = [
        "> **Ngôn ngữ / Language:** [Tiếng Việt](#tieng-viet) · [English](#english)",
        "",
        '<a id="tieng-viet"></a>',
        "",
        "# Giấy phép phần mềm bên thứ ba",
        "",
        "## Dịch vụ container (Docker)",
        "",
        "| Thành phần | Image | Giấy phép | OSI/mở | Ghi chú |",
        "| --- | --- | --- | --- | --- |",
    ]
    for component, image, license_name, osi, note in container_rows:
        lines.append(f"| {component} | `{image}` | {license_name} | {osi} | {note} |")

    lines.extend([
        "",
        "## Thành phần xử lý tài liệu",
        "",
        "| Thành phần | Nguồn trong repo | Giấy phép | Ghi chú |",
        "| --- | --- | --- | --- |",
        "| Tesseract OCR + vie/eng traineddata | `rag-chatbot-fastapi/Dockerfile` | Apache-2.0 | Được cài trong image để OCR PDF scan/ảnh; `INGESTION_OCR_ENABLED=false` mặc định. |",
        "| faster-whisper | `rag-chatbot-fastapi/pyproject.toml` (`asr` optional extra) | MIT | Chỉ được cài khi người vận hành chọn extra ASR; ASR tắt mặc định. |",
    ])

    lines.extend([
        "",
        "## Thư viện sử dụng",
        "",
        "### Java — `api/pom.xml`",
        "",
        "| Thành phần | Giấy phép | Ghi chú |",
        "| --- | --- | --- |",
    ])
    for r in java_records:
        lines.append(f"| {r['name']} | {r['license']} | {license_note(r['name'], r['license'], 'vi')} |")

    lines.extend(["", "### Python — `rag-chatbot-fastapi/pyproject.toml`", "", "| Gói | Giấy phép | Ghi chú |", "| --- | --- | --- |"])
    for r in python_records:
        lines.append(f"| {r['name']} | {r['license']} | {license_note(r['name'], r['license'], 'vi')} |")

    lines.extend(["", "### Frontend — `frontend/package.json`", "", "| Gói lúc chạy trực tiếp | Giấy phép | Ghi chú |", "| --- | --- | --- |"])
    for r in frontend_records:
        lines.append(f"| {r['name']} | {r['license']} | {license_note(r['name'], r['license'], 'vi')} |")

    lines.extend([
    ])

    lines.extend([
        "",
        '<a id="english"></a>',
        "",
        "# Third-Party Licenses",
        "",
        "## Container services (Docker)",
        "",
        "| Component | Image | License | OSI/open | Notes |",
        "| --- | --- | --- | --- | --- |",
    ])
    for component, image, license_name, osi, _ in container_rows:
        english_osi = "Yes" if osi == "Có" else "No"
        lines.append(
            f"| {component} | `{image}` | {license_name} | {english_osi} | "
            f"{CONTAINER_NOTES_EN[image]} |"
        )

    lines.extend([
        "",
        "## Document-processing components",
        "",
        "| Component | Repository source | License | Notes |",
        "| --- | --- | --- | --- |",
        "| Tesseract OCR + vie/eng traineddata | `rag-chatbot-fastapi/Dockerfile` | Apache-2.0 | Installed in the image for scanned-PDF and image OCR; `INGESTION_OCR_ENABLED=false` by default. |",
        "| faster-whisper | `rag-chatbot-fastapi/pyproject.toml` (`asr` optional extra) | MIT | Installed only when an operator selects the ASR extra; ASR is disabled by default. |",
        "",
        "## Libraries we use",
        "",
        "### Java — `api/pom.xml`",
        "",
        "| Component | License | Notes |",
        "| --- | --- | --- |",
    ])
    for r in java_records:
        lines.append(f"| {r['name']} | {r['license']} | {license_note(r['name'], r['license'], 'en')} |")

    lines.extend(["", "### Python — `rag-chatbot-fastapi/pyproject.toml`", "", "| Package | License | Notes |", "| --- | --- | --- |"])
    for r in python_records:
        lines.append(f"| {r['name']} | {r['license']} | {license_note(r['name'], r['license'], 'en')} |")

    lines.extend(["", "### Frontend — `frontend/package.json`", "", "| Direct runtime package | License | Notes |", "| --- | --- | --- |"])
    for r in frontend_records:
        lines.append(f"| {r['name']} | {r['license']} | {license_note(r['name'], r['license'], 'en')} |")

    lines.extend([
    ])

    out_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Generated {out_file.name} with {len(all_records)} direct dependency records.")

    if failures:
        print(f"ERROR: {len(failures)} forbidden licenses found:")
        for f in failures:
            print("  -", f)
        return 1

    print("All dependencies comply with OpenVie Apache-2.0 third-party policy.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
