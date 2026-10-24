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

from app.generated import openvie_ai_v1_pb2 as pb


def test_inference_service_exposes_only_the_three_rag_methods() -> None:
    methods = [method.name for method in pb.DESCRIPTOR.services_by_name["InferenceService"].methods]
    assert methods == [
        "GenerateAnswer",
        "ListDocumentUnits",
        "DeleteDocumentIndex",
    ]


def test_generate_answer_descriptor_field_numbers() -> None:
    request = pb.GenerateAnswerRequest.DESCRIPTOR
    assert {field.name: field.number for field in request.fields} == {
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
        "document_ids": 14,
    }
    assert pb.DESCRIPTOR.message_types_by_name.get("TicketDraft") is None
    assert pb.DESCRIPTOR.message_types_by_name.get("PrepareInterviewSessionRequest") is None
    assert {field.name for field in pb.GenerateAnswerResponse.DESCRIPTOR.fields} == {
        "generation_id",
        "authoritative_revision",
        "answer",
        "citations",
        "input_tokens",
        "output_tokens",
        "cache_tier",
        "avoided_input_tokens",
        "avoided_output_tokens",
        "suggested_questions",
    }
