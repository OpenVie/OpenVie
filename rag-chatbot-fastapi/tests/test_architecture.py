from __future__ import annotations

import ast
from pathlib import Path

from app.main import create_app

ROOT = Path(__file__).resolve().parents[1]


def test_fastapi_runtime_has_no_postgres_dependency_or_business_sql() -> None:
    source = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (ROOT / "app").rglob("*.py")
        if "generated" not in path.parts
    )
    project = (ROOT / "pyproject.toml").read_text(encoding="utf-8")
    forbidden = ("psycopg", "POSTGRES_URL", "PostgresChatSessionStore", "SELECT search_revision")
    assert all(value not in source for value in forbidden)
    assert "psycopg" not in project


def test_http_surface_exposes_only_health_and_metrics() -> None:
    paths = {route.path for route in create_app().routes}
    assert paths == {
        "/health",
        "/health/live",
        "/health/ready",
        "/metrics",
    }


def _imports(path: Path) -> list[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    values: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module:
            values.append(node.module)
        elif isinstance(node, ast.Import):
            values.extend(alias.name for alias in node.names)
    return values


def test_cross_module_imports_use_api_boundaries_and_graph_is_acyclic() -> None:
    allowed = {
        "generation": {"retrieval", "model"},
        "retrieval": {"index", "graph", "model"},
        "ingestion": {"index", "graph", "model"},
        "index": set(),
        "graph": set(),
        "model": set(),
    }
    violations: list[str] = []
    modules = ROOT / "app" / "modules"
    for path in modules.rglob("*.py"):
        owner = path.relative_to(modules).parts[0]
        for imported in _imports(path):
            parts = imported.split(".")
            if len(parts) < 3 or parts[:2] != ["app", "modules"]:
                continue
            dependency = parts[2]
            if dependency == owner:
                continue
            if dependency not in allowed[owner]:
                violations.append(f"{path.relative_to(ROOT)} -> {imported}")
                continue
            if len(parts) < 4 or parts[3] != "api":
                violations.append(f"{path.relative_to(ROOT)} -> {imported}")
    assert violations == []


def test_api_packages_are_sdk_and_implementation_free() -> None:
    forbidden = (
        "internal",
        "transport",
        "app.generated",
        "fastapi",
        "grpc",
        "redis",
        "qdrant_client",
        "kuzu",
        "boto3",
        "aio_pika",
    )
    violations: list[str] = []
    for path in (ROOT / "app" / "modules").glob("*/api/**/*.py"):
        for imported in _imports(path):
            if any(value in imported for value in forbidden):
                violations.append(f"{path.relative_to(ROOT)} -> {imported}")
    assert violations == []


def test_persistent_sdk_and_generated_import_ownership() -> None:
    violations: list[str] = []
    for path in (ROOT / "app").rglob("*.py"):
        relative = path.relative_to(ROOT / "app")
        source = path.read_text(encoding="utf-8")
        parts = relative.parts
        if "qdrant_client" in source and not (
            parts[:3] == ("modules", "index", "internal")
            or parts[:3] == ("modules", "generation", "internal")
        ):
            violations.append(f"Qdrant: {relative}")
        if "import kuzu" in source and parts[:3] != ("modules", "graph", "internal"):
            violations.append(f"Kuzu: {relative}")
        if "aio_pika" in source and parts[:3] != ("modules", "ingestion", "transport"):
            violations.append(f"aio-pika: {relative}")
        if "import boto3" in source and parts[:2] != ("common", "storage.py"):
            violations.append(f"boto3: {relative}")
        if "app.generated" in source and not (
            parts[0] == "bootstrap" or "transport" in parts or parts[0] == "generated"
        ):
            violations.append(f"generated: {relative}")
    assert violations == []


def test_legacy_implementation_packages_are_removed() -> None:
    legacy = (
        "rag",
        "ingestion",
        "infrastructure",
        "application",
        "domain",
        "core",
        "api",
    )
    assert all(not any((ROOT / "app" / name).rglob("*.py")) for name in legacy)
    assert not (ROOT / "app" / "graph.py").exists()
    assert not (ROOT / "app" / "grpc_service.py").exists()
    assert not (ROOT / "app" / "document_units.py").exists()


def test_common_and_maintenance_do_not_reach_into_capability_internals() -> None:
    for path in (ROOT / "app" / "common").rglob("*.py"):
        assert all(not item.startswith("app.modules") for item in _imports(path))
    for path in (ROOT / "app" / "maintenance").rglob("*.py"):
        assert all(".internal" not in item for item in _imports(path))
