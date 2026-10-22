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


def test_ocr_settings_default_off_and_stay_off() -> None:
    configured = Settings(_env_file=())
    assert configured.INGESTION_OCR_ENABLED is False
    assert configured.INGESTION_OCR_PROVIDER == "tesseract"
    assert configured.INGESTION_OCR_LANGUAGES == "vie+eng"
    assert configured.INGESTION_OCR_DPI == 300
    assert configured.INGESTION_OCR_MIN_TEXT_CHARS_PER_PAGE == 32
    assert configured.INGESTION_OCR_MAX_PAGES == 300
    assert configured.INGESTION_OCR_MAX_MEGAPIXELS_PER_PAGE == 25.0
    assert configured.INGESTION_OCR_TIMEOUT_SECONDS_PER_PAGE == 60.0
    assert configured.INGESTION_OCR_PAGE_CONCURRENCY == 2
    assert configured.INGESTION_OCR_PSM == 3
    assert configured.INGESTION_OCR_EMBEDDED_IMAGES is False


def test_ocr_setting_bounds_are_validated() -> None:
    base = {"_env_file": (), "INGESTION_OCR_ENABLED": True}
    with pytest.raises(ValidationError, match="INGESTION_OCR_LANGUAGES"):
        Settings(**base, INGESTION_OCR_LANGUAGES="   ")
    with pytest.raises(ValidationError, match="INGESTION_OCR_DPI"):
        Settings(**base, INGESTION_OCR_DPI=71)
    with pytest.raises(ValidationError, match="INGESTION_OCR_DPI"):
        Settings(**base, INGESTION_OCR_DPI=601)
    with pytest.raises(ValidationError, match="INGESTION_OCR_MIN_TEXT_CHARS_PER_PAGE"):
        Settings(**base, INGESTION_OCR_MIN_TEXT_CHARS_PER_PAGE=-1)
    with pytest.raises(ValidationError, match="INGESTION_OCR_MAX_PAGES"):
        Settings(**base, INGESTION_OCR_MAX_PAGES=0)
    with pytest.raises(ValidationError, match="INGESTION_OCR_MAX_PAGES"):
        Settings(**base, INGESTION_OCR_MAX_PAGES=5001)
    with pytest.raises(ValidationError, match="INGESTION_OCR_MAX_MEGAPIXELS_PER_PAGE"):
        Settings(**base, INGESTION_OCR_MAX_MEGAPIXELS_PER_PAGE=200.1)
    with pytest.raises(ValidationError, match="INGESTION_OCR_TIMEOUT_SECONDS_PER_PAGE"):
        Settings(**base, INGESTION_OCR_TIMEOUT_SECONDS_PER_PAGE=0)
    with pytest.raises(ValidationError, match="INGESTION_OCR_PAGE_CONCURRENCY"):
        Settings(**base, INGESTION_OCR_PAGE_CONCURRENCY=17)
    with pytest.raises(ValidationError, match="INGESTION_OCR_PSM"):
        Settings(**base, INGESTION_OCR_PSM=14)


def test_ocr_vlm_provider_requires_model_id() -> None:
    with pytest.raises(ValidationError, match="OCR_VLM_MODEL_ID"):
        Settings(
            _env_file=(),
            INGESTION_OCR_ENABLED=True,
            INGESTION_OCR_PROVIDER="ollama-vlm",
        )
    configured = Settings(
        _env_file=(),
        INGESTION_OCR_ENABLED=True,
        INGESTION_OCR_PROVIDER="ollama-vlm",
        OCR_VLM_MODEL_ID="llava:7b",
    )
    assert configured.OCR_VLM_MODEL_ID == "llava:7b"


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

