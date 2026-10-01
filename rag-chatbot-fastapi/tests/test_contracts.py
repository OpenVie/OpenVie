from __future__ import annotations

import json
from pathlib import Path

from app.contracts.document_ingestion_v1 import DocumentIngestRequestedEvent
from app.generated import cacanode_ai_v1_pb2 as pb
from app.modules.ingestion.api.event import status_event

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "document-ingestion" / "v1"


def test_grpc_descriptor_is_wire_compatible() -> None:
    service = pb.DESCRIPTOR.services_by_name["InferenceService"]
    assert [method.name for method in service.methods] == [
        "GenerateAnswer",
        "ListDocumentUnits",
        "DeleteDocumentIndex",
    ]
    expected = {
        "GenerateAnswerRequest": {
            "generation_id": 1,
            "turn_id": 2,
            "tenant_id": 3,
            "chatbot_id": 4,
            "knowledge_base_id": 5,
            "authoritative_revision": 6,
            "channel": 7,
            "locale": 8,
            "question": 9,
            "prior_messages": 10,
            "tenant_name": 11,
            "prompt_schema_version": 12,
            "trace": 13,
        },
        "GenerateAnswerResponse": {
            "generation_id": 1,
            "authoritative_revision": 2,
            "answer": 3,
            "citations": 4,
            "input_tokens": 5,
            "output_tokens": 6,
            "cache_tier": 7,
            "avoided_input_tokens": 8,
            "avoided_output_tokens": 9,
        },
        "DocumentUnit": {
            "unit_id": 1,
            "chunk_index": 2,
            "text": 3,
            "source_name": 4,
            "modality": 5,
            "block_type": 6,
            "section_path": 7,
            "heading_context": 8,
            "page_number": 9,
            "sheet_name": 10,
            "cell_range": 11,
            "table_id": 12,
            "source_start": 13,
            "source_end": 14,
        },
    }
    for message_name, fields in expected.items():
        descriptor = pb.DESCRIPTOR.message_types_by_name[message_name]
        assert {field.name: field.number for field in descriptor.fields} == fields
    response = pb.DESCRIPTOR.message_types_by_name["GenerateAnswerResponse"]
    assert {
        field.name
        for field in response.fields
        if field.has_presence
    } == {
        "input_tokens",
        "output_tokens",
        "avoided_input_tokens",
        "avoided_output_tokens",
    }
    assert "VisibilityMode" not in pb.DESCRIPTOR.enum_types_by_name


def test_cross_language_ingestion_fixtures_match_canonical_schemas() -> None:
    request_schema = json.loads((CONTRACTS / "request.schema.json").read_text())
    status_schema = json.loads((CONTRACTS / "status.schema.json").read_text())
    request = (CONTRACTS / "fixtures" / "request.json").read_bytes()
    parsed = DocumentIngestRequestedEvent.parse_payload(request)
    assert parsed.schema_version == "1.0"
    request_payload = json.loads(request)
    assert set(request_payload) == set(request_schema["required"])
    for name in ("status-processing.json", "status-completed.json", "status-failed.json"):
        payload = json.loads((CONTRACTS / "fixtures" / name).read_text())
        assert set(payload) == set(status_schema["required"])
        assert payload["status"] in status_schema["properties"]["status"]["enum"]


def test_status_event_identity_is_stable_for_republication() -> None:
    values = {
        "schema_version": "1.0",
        "job_id": "22222222-2222-4222-8222-222222222222",
        "tenant_id": "33333333-3333-4333-8333-333333333333",
        "document_id": "55555555-5555-4555-8555-555555555555",
        "status": "COMPLETED",
        "chunk_count": 12,
    }
    assert status_event(**values)["event_id"] == status_event(**values)["event_id"]
