from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.bootstrap.settings import Settings


def test_ai_settings_have_no_postgres_or_business_auth_fields() -> None:
    configured = Settings(_env_file=())
    assert not any(name.startswith("POSTGRES_") for name in type(configured).model_fields)
    assert "TOKEN_KEY" not in type(configured).model_fields
    assert "INTEGRATION_TOKEN_PEPPER" not in type(configured).model_fields


def test_grpc_defaults_are_plaintext_for_local_development() -> None:
    configured = Settings(_env_file=())
    assert configured.GRPC_PLAINTEXT is True
    assert configured.GRPC_PORT == 50051
    assert configured.GENERATION_RESULT_CACHE_TTL_SECONDS == 600
    assert configured.READINESS_DIAGNOSTICS_TIMEOUT_SECONDS == 0.5
    assert configured.READINESS_INGESTION_SCAN_LIMIT == 200


def test_qwen_is_ready_with_base_url_and_exact_model_id() -> None:
    configured = Settings(
        _env_file=(),
        LLM_PROVIDER="qwen",
        LLM_BASE_URL="http://127.0.0.1:8080/v1",
        LLM_MODEL_ID="mlx-community/Qwen2.5-14B-Instruct-4bit",
    )

    assert configured.model_configured is True


def test_readiness_diagnostic_bounds_are_validated() -> None:
    with pytest.raises(ValidationError, match="diagnostics timeout"):
        Settings(_env_file=(), READINESS_DIAGNOSTICS_TIMEOUT_SECONDS=2.1)
    with pytest.raises(ValidationError, match="scan limit"):
        Settings(_env_file=(), READINESS_INGESTION_SCAN_LIMIT=0)


def test_graph_hop_bounds_are_validated() -> None:
    assert Settings(_env_file=(), GRAPH_MAX_HOPS=0).GRAPH_MAX_HOPS == 0
    assert Settings(_env_file=(), GRAPH_MAX_HOPS=3).GRAPH_MAX_HOPS == 3
    with pytest.raises(ValidationError, match="GRAPH_MAX_HOPS"):
        Settings(_env_file=(), GRAPH_MAX_HOPS=-1)
    with pytest.raises(ValidationError, match="GRAPH_MAX_HOPS"):
        Settings(_env_file=(), GRAPH_MAX_HOPS=4)


def test_mtls_requires_complete_server_material() -> None:
    with pytest.raises(ValidationError, match="mTLS material"):
        Settings(_env_file=(), GRPC_PLAINTEXT=False)


def test_all_bootstrap_module_configs_bind_from_settings() -> None:
    from app.bootstrap.configuration import (
        generation_config,
        graph_config,
        index_config,
        ingestion_transport_config,
        model_config,
        retrieval_config,
        storage_config,
    )

    settings = Settings(_env_file=())
    for loader in (
        generation_config,
        graph_config,
        index_config,
        ingestion_transport_config,
        model_config,
        retrieval_config,
        storage_config,
    ):
        config = loader(settings)
        assert config is not None

