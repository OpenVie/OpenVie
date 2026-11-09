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

import hashlib
import json

from app.bootstrap.settings import Settings
from app.modules.generation.api import Citation
from app.modules.generation.internal.models import ChatMessage
from app.modules.generation.internal.service import _SYSTEM_PROMPT, RagChatService
from app.modules.retrieval.api import RetrievedKnowledgeUnit

EXPECTED_SYSTEM_PROMPT_SHA256 = "9e48c2d6da19cc57c7abcd6eb112eaaf7d66eed0c948ee0bc99f8c43e42c42f6"
EXPECTED_PROMPT_MESSAGES_SHA256 = "d39dd55fa8c8e849ad6e70971979e4734de38888705855ad463903296f6b7b8b"


def test_system_prompt_is_invariant() -> None:
    digest = hashlib.sha256(_SYSTEM_PROMPT.encode("utf-8")).hexdigest()
    assert (
        digest == EXPECTED_SYSTEM_PROMPT_SHA256
    ), "CI Gate G-3 violation: _SYSTEM_PROMPT has been altered without authorization."


def test_prompt_messages_structure_is_invariant() -> None:
    c1 = RetrievedKnowledgeUnit(
        document_id="doc1",
        source_name="doc1.pdf",
        page_number=1,
        chunk_index=0,
        text="Hello world",
        score=0.9,
        unit_id="u1",
    )
    cit1 = Citation(
        id="[S1]",
        document_id="doc1",
        source_name="doc1.pdf",
        page_number=1,
        chunk_index=0,
        score=0.9,
        snippet="Hello world",
    )
    service = RagChatService(chat_model=None, embedder=None, retriever=None, settings=Settings())  # type: ignore[arg-type]
    messages = service._prompt_messages(
        question="What is this?",
        chunks=[c1],
        citations=[cit1],
        conversation=[ChatMessage(role="user", content="Hi", sequence_number=1)],
    )
    canonical = json.dumps(messages, sort_keys=True)
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    assert (
        digest == EXPECTED_PROMPT_MESSAGES_SHA256
    ), "CI Gate G-3 violation: _prompt_messages output structure has been altered."
