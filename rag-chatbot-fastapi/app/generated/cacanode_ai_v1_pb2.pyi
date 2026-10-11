from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class PriorMessage(_message.Message):
    __slots__ = ("role", "content")
    ROLE_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    role: str
    content: str
    def __init__(self, role: _Optional[str] = ..., content: _Optional[str] = ...) -> None: ...

class TraceMetadata(_message.Message):
    __slots__ = ("request_id", "trace_id", "parent_span_id", "baggage")
    class BaggageEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    TRACE_ID_FIELD_NUMBER: _ClassVar[int]
    PARENT_SPAN_ID_FIELD_NUMBER: _ClassVar[int]
    BAGGAGE_FIELD_NUMBER: _ClassVar[int]
    request_id: str
    trace_id: str
    parent_span_id: str
    baggage: _containers.ScalarMap[str, str]
    def __init__(self, request_id: _Optional[str] = ..., trace_id: _Optional[str] = ..., parent_span_id: _Optional[str] = ..., baggage: _Optional[_Mapping[str, str]] = ...) -> None: ...

class GenerateAnswerRequest(_message.Message):
    __slots__ = ("generation_id", "turn_id", "tenant_id", "chatbot_id", "knowledge_base_id", "authoritative_revision", "channel", "locale", "question", "prior_messages", "tenant_name", "prompt_schema_version", "trace")
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    TURN_ID_FIELD_NUMBER: _ClassVar[int]
    TENANT_ID_FIELD_NUMBER: _ClassVar[int]
    CHATBOT_ID_FIELD_NUMBER: _ClassVar[int]
    KNOWLEDGE_BASE_ID_FIELD_NUMBER: _ClassVar[int]
    AUTHORITATIVE_REVISION_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_FIELD_NUMBER: _ClassVar[int]
    LOCALE_FIELD_NUMBER: _ClassVar[int]
    QUESTION_FIELD_NUMBER: _ClassVar[int]
    PRIOR_MESSAGES_FIELD_NUMBER: _ClassVar[int]
    TENANT_NAME_FIELD_NUMBER: _ClassVar[int]
    PROMPT_SCHEMA_VERSION_FIELD_NUMBER: _ClassVar[int]
    TRACE_FIELD_NUMBER: _ClassVar[int]
    generation_id: str
    turn_id: str
    tenant_id: str
    chatbot_id: str
    knowledge_base_id: str
    authoritative_revision: int
    channel: str
    locale: str
    question: str
    prior_messages: _containers.RepeatedCompositeFieldContainer[PriorMessage]
    tenant_name: str
    prompt_schema_version: str
    trace: TraceMetadata
    def __init__(self, generation_id: _Optional[str] = ..., turn_id: _Optional[str] = ..., tenant_id: _Optional[str] = ..., chatbot_id: _Optional[str] = ..., knowledge_base_id: _Optional[str] = ..., authoritative_revision: _Optional[int] = ..., channel: _Optional[str] = ..., locale: _Optional[str] = ..., question: _Optional[str] = ..., prior_messages: _Optional[_Iterable[_Union[PriorMessage, _Mapping]]] = ..., tenant_name: _Optional[str] = ..., prompt_schema_version: _Optional[str] = ..., trace: _Optional[_Union[TraceMetadata, _Mapping]] = ...) -> None: ...

class Citation(_message.Message):
    __slots__ = ("id", "document_id", "source_name", "page_number", "chunk_index", "score", "snippet", "unit_id", "modality", "section_path", "block_type", "sheet_name", "cell_range", "table_id")
    ID_FIELD_NUMBER: _ClassVar[int]
    DOCUMENT_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_NAME_FIELD_NUMBER: _ClassVar[int]
    PAGE_NUMBER_FIELD_NUMBER: _ClassVar[int]
    CHUNK_INDEX_FIELD_NUMBER: _ClassVar[int]
    SCORE_FIELD_NUMBER: _ClassVar[int]
    SNIPPET_FIELD_NUMBER: _ClassVar[int]
    UNIT_ID_FIELD_NUMBER: _ClassVar[int]
    MODALITY_FIELD_NUMBER: _ClassVar[int]
    SECTION_PATH_FIELD_NUMBER: _ClassVar[int]
    BLOCK_TYPE_FIELD_NUMBER: _ClassVar[int]
    SHEET_NAME_FIELD_NUMBER: _ClassVar[int]
    CELL_RANGE_FIELD_NUMBER: _ClassVar[int]
    TABLE_ID_FIELD_NUMBER: _ClassVar[int]
    id: str
    document_id: str
    source_name: str
    page_number: int
    chunk_index: int
    score: float
    snippet: str
    unit_id: str
    modality: str
    section_path: _containers.RepeatedScalarFieldContainer[str]
    block_type: str
    sheet_name: str
    cell_range: str
    table_id: str
    def __init__(self, id: _Optional[str] = ..., document_id: _Optional[str] = ..., source_name: _Optional[str] = ..., page_number: _Optional[int] = ..., chunk_index: _Optional[int] = ..., score: _Optional[float] = ..., snippet: _Optional[str] = ..., unit_id: _Optional[str] = ..., modality: _Optional[str] = ..., section_path: _Optional[_Iterable[str]] = ..., block_type: _Optional[str] = ..., sheet_name: _Optional[str] = ..., cell_range: _Optional[str] = ..., table_id: _Optional[str] = ...) -> None: ...

class GenerateAnswerResponse(_message.Message):
    __slots__ = ("generation_id", "authoritative_revision", "answer", "citations", "input_tokens", "output_tokens", "cache_tier", "avoided_input_tokens", "avoided_output_tokens")
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    AUTHORITATIVE_REVISION_FIELD_NUMBER: _ClassVar[int]
    ANSWER_FIELD_NUMBER: _ClassVar[int]
    CITATIONS_FIELD_NUMBER: _ClassVar[int]
    INPUT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    OUTPUT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    CACHE_TIER_FIELD_NUMBER: _ClassVar[int]
    AVOIDED_INPUT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    AVOIDED_OUTPUT_TOKENS_FIELD_NUMBER: _ClassVar[int]
    generation_id: str
    authoritative_revision: int
    answer: str
    citations: _containers.RepeatedCompositeFieldContainer[Citation]
    input_tokens: int
    output_tokens: int
    cache_tier: str
    avoided_input_tokens: int
    avoided_output_tokens: int
    def __init__(self, generation_id: _Optional[str] = ..., authoritative_revision: _Optional[int] = ..., answer: _Optional[str] = ..., citations: _Optional[_Iterable[_Union[Citation, _Mapping]]] = ..., input_tokens: _Optional[int] = ..., output_tokens: _Optional[int] = ..., cache_tier: _Optional[str] = ..., avoided_input_tokens: _Optional[int] = ..., avoided_output_tokens: _Optional[int] = ...) -> None: ...

class GenerateAnswerEvent(_message.Message):
    __slots__ = ("content", "completed")
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    COMPLETED_FIELD_NUMBER: _ClassVar[int]
    content: str
    completed: GenerateAnswerResponse
    def __init__(self, content: _Optional[str] = ..., completed: _Optional[_Union[GenerateAnswerResponse, _Mapping]] = ...) -> None: ...

class ListDocumentUnitsRequest(_message.Message):
    __slots__ = ("tenant_id", "knowledge_base_id", "document_id", "trace")
    TENANT_ID_FIELD_NUMBER: _ClassVar[int]
    KNOWLEDGE_BASE_ID_FIELD_NUMBER: _ClassVar[int]
    DOCUMENT_ID_FIELD_NUMBER: _ClassVar[int]
    TRACE_FIELD_NUMBER: _ClassVar[int]
    tenant_id: str
    knowledge_base_id: str
    document_id: str
    trace: TraceMetadata
    def __init__(self, tenant_id: _Optional[str] = ..., knowledge_base_id: _Optional[str] = ..., document_id: _Optional[str] = ..., trace: _Optional[_Union[TraceMetadata, _Mapping]] = ...) -> None: ...

class DocumentUnit(_message.Message):
    __slots__ = ("unit_id", "chunk_index", "text", "source_name", "modality", "block_type", "section_path", "heading_context", "page_number", "sheet_name", "cell_range", "table_id", "source_start", "source_end")
    UNIT_ID_FIELD_NUMBER: _ClassVar[int]
    CHUNK_INDEX_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    SOURCE_NAME_FIELD_NUMBER: _ClassVar[int]
    MODALITY_FIELD_NUMBER: _ClassVar[int]
    BLOCK_TYPE_FIELD_NUMBER: _ClassVar[int]
    SECTION_PATH_FIELD_NUMBER: _ClassVar[int]
    HEADING_CONTEXT_FIELD_NUMBER: _ClassVar[int]
    PAGE_NUMBER_FIELD_NUMBER: _ClassVar[int]
    SHEET_NAME_FIELD_NUMBER: _ClassVar[int]
    CELL_RANGE_FIELD_NUMBER: _ClassVar[int]
    TABLE_ID_FIELD_NUMBER: _ClassVar[int]
    SOURCE_START_FIELD_NUMBER: _ClassVar[int]
    SOURCE_END_FIELD_NUMBER: _ClassVar[int]
    unit_id: str
    chunk_index: int
    text: str
    source_name: str
    modality: str
    block_type: str
    section_path: _containers.RepeatedScalarFieldContainer[str]
    heading_context: str
    page_number: int
    sheet_name: str
    cell_range: str
    table_id: str
    source_start: int
    source_end: int
    def __init__(self, unit_id: _Optional[str] = ..., chunk_index: _Optional[int] = ..., text: _Optional[str] = ..., source_name: _Optional[str] = ..., modality: _Optional[str] = ..., block_type: _Optional[str] = ..., section_path: _Optional[_Iterable[str]] = ..., heading_context: _Optional[str] = ..., page_number: _Optional[int] = ..., sheet_name: _Optional[str] = ..., cell_range: _Optional[str] = ..., table_id: _Optional[str] = ..., source_start: _Optional[int] = ..., source_end: _Optional[int] = ...) -> None: ...

class ListDocumentUnitsResponse(_message.Message):
    __slots__ = ("units",)
    UNITS_FIELD_NUMBER: _ClassVar[int]
    units: _containers.RepeatedCompositeFieldContainer[DocumentUnit]
    def __init__(self, units: _Optional[_Iterable[_Union[DocumentUnit, _Mapping]]] = ...) -> None: ...

class DeleteDocumentIndexRequest(_message.Message):
    __slots__ = ("tenant_id", "knowledge_base_id", "document_id", "trace")
    TENANT_ID_FIELD_NUMBER: _ClassVar[int]
    KNOWLEDGE_BASE_ID_FIELD_NUMBER: _ClassVar[int]
    DOCUMENT_ID_FIELD_NUMBER: _ClassVar[int]
    TRACE_FIELD_NUMBER: _ClassVar[int]
    tenant_id: str
    knowledge_base_id: str
    document_id: str
    trace: TraceMetadata
    def __init__(self, tenant_id: _Optional[str] = ..., knowledge_base_id: _Optional[str] = ..., document_id: _Optional[str] = ..., trace: _Optional[_Union[TraceMetadata, _Mapping]] = ...) -> None: ...

class DeleteDocumentIndexResponse(_message.Message):
    __slots__ = ("deleted",)
    DELETED_FIELD_NUMBER: _ClassVar[int]
    deleted: bool
    def __init__(self, deleted: bool = ...) -> None: ...
