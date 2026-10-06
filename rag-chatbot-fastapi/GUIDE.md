# Python AI modular-monolith guide

> **Ngôn ngữ / Language:** [Tiếng Việt](#hướng-dẫn-kiến-trúc-modular-monolith-python-ai--tiếng-việt) · [English](#python-ai-modular-monolith-guide-1)

---

## Hướng dẫn kiến trúc Modular-Monolith Python AI — Tiếng Việt

Tài liệu này giải thích cấu trúc dịch vụ Python AI trong `app` và quy tắc phát triển tính năng mới mà không làm vỡ ranh giới module.

Dịch vụ là một **Modular Monolith**: một cây mã nguồn chứa sáu module năng lực, được kết hợp thành ba vai trò runtime. Ranh giới module được kiểm tra tự động qua bộ test kiến trúc trên mỗi lượt test.

### Bốn nguyên tắc bất khả xâm phạm

1. **Giao tiếp liên module chỉ qua `<module>.api` hoặc `<module>.api.event`:** Không bao giờ import gói `internal`, `transport`, service cụ thể, model cấu hình, hay client SDK của module khác.
2. **Ranh giới đồng bộ sử dụng `typing.Protocol`:** Gói `api` sở hữu các Protocol, Command, Result, dataclass frozen, `StrEnum`, và Exception ranh giới. Không để lộ model Qdrant/Kuzu hay request FastAPI.
3. **Mỗi tài nguyên lưu trữ hoặc namespace do một module độc quyền sở hữu:** Không truy cập trực tiếp tài nguyên của module khác (ví dụ: không gọi thẳng `qdrant_client` nếu không thuộc module `index`).
4. **Phản ứng bất đồng bộ sử dụng sự kiện bền vững:** Luồng sự kiện chính hiện tại là quy trình hấp thụ tài liệu (RabbitMQ kèm checkpoint Redis bền vững).

### Sơ đồ phụ thuộc cho phép (Acyclic Graph)

```text
generation -> retrieval.api, model.api, common
retrieval  -> index.api, graph.api, model.api, common
ingestion  -> index.api, graph.api, model.api, common.storage
index      -> common
graph      -> common
model      -> common
bootstrap  -> tất cả các module (chỉ wiring)
maintenance-> module API và common
```

### Danh mục Module và Quyền sở hữu Tài nguyên

| Module | Trách nhiệm | Ranh giới API đồng bộ | Tài nguyên sở hữu độc quyền |
| --- | --- | --- | --- |
| `generation` | Sinh câu trả lời RAG, trích dẫn, lập kế hoạch truy vấn, tính toán bảng tính, bộ đệm câu trả lời | `GenerationApi`, `GenerationCacheMaintenanceApi` | Collection Qdrant `semantic_answer_cache_v1`, Redis namespace `generation` |
| `retrieval` | Kế hoạch truy vấn, dung hợp RRF, xếp hạng lại (reranking), mở rộng ngữ cảnh lân cận và bảng biểu | `RetrievalApi` | Redis namespace `retrieval` |
| `ingestion` | Phân tích file, chunking, trích xuất thực thể, điều phối thay thế chỉ mục, checkpoint worker | `IngestionApi`, `DocumentIndexLifecycleApi`, `IngestionCheckpointMaintenanceApi` | Hàng đợi RabbitMQ, Redis namespace `ccn:v1:ingestion:*` |
| `index` | Thay thế/xóa chỉ mục, tìm kiếm dense/sparse, tra cứu dòng bảng | `KnowledgeIndexCommandApi`, `KnowledgeIndexQueryApi` | Collection Qdrant `knowledge_units_v2` |
| `graph` | Quản lý đồ thị tri thức, duyệt đường đi quan hệ | `GraphProjectionApi`, `GraphQueryApi` | Cơ sở dữ liệu Kuzu, HTTP graph-service |
| `model` | Adapter mô hình ngôn ngữ (Ollama/Qwen), embedding dense/sparse | `ChatModelApi`, `TextEmbeddingApi`, `SparseEmbeddingApi` | Redis namespace `model` |

### Quy trình hấp thụ tài liệu bền vững

Checkpoint quản lý trạng thái qua Redis AOF (`ccn:v1:ingestion:...`) bằng các script Lua nguyên tử:
```text
CLAIMED -> PROCESSING_PUBLISHED -> INDEX_REPLACED -> GRAPH_REPLACED
        -> COMPLETED_PUBLISHED -> COMPLETE
```
Nếu có sự cố giữa chừng, lệnh phục hồi có giới hạn sẽ phát lại yêu cầu chưa hoàn tất:
```bash
make -C rag-chatbot-fastapi recover-ingestion-checkpoints
```

### Bảng vị trí đặt mã nguồn mới

| Mã nguồn mới | Vị trí chính xác |
| --- | --- |
| Protocol gọi đồng bộ liên module | Gói `<module>.api` của module sở hữu |
| Command / Result / Enum ranh giới | Gói `<module>.api` của module sở hữu |
| Logic nghiệp vụ nội bộ | Gói `<module>.internal` |
| Adapter transport (gRPC, HTTP, RabbitMQ) | Gói `<module>.transport` |
| Nạp cấu hình và khởi tạo đối tượng | Gói `bootstrap` |
| Hạ tầng kỹ thuật dùng chung | Gói `common` |
| Lệnh vận hành bảo trì | Gói `maintenance` (chỉ gọi module API) |

### Kiểm chứng kiến trúc

Kiểm tra ranh giới kiến trúc bằng pytest:
```bash
make -C rag-chatbot-fastapi check PYTHON=python3.11
```
`tests/test_architecture.py` kiểm tra cấu trúc AST và từ chối mọi trường hợp import chéo ranh giới hoặc rò rỉ SDK client.

---

# Python AI modular-monolith guide

This guide covers how the Python AI service in `app` is organized and how to add capabilities without breaking module isolation.

The service is a **Modular Monolith**: one codebase containing six capability modules composed into three runtime roles. Boundaries are enforced on every test run.

## The four non-negotiable rules

1. **Cross modules only through `<module>.api` or `<module>.api.event`:** Never import another module's `internal`, `transport`, concrete service, or SDK client directly.
2. **Synchronous boundaries use capability protocols:** Expose small `typing.Protocol` interfaces, frozen dataclasses, and boundary exceptions. Never leak Qdrant/Kuzu models or web request objects.
3. **One module exclusively owns each persistent resource:** No direct access to another module's Qdrant collection, Kuzu database, or Redis key family.
4. **Independent reactions use durable events:** Document ingestion uses RabbitMQ and Redis checkpoints for crash-resilient asynchronous processing.

## Allowed dependency graph

```text
generation -> retrieval.api, model.api, common
retrieval  -> index.api, graph.api, model.api, common
ingestion  -> index.api, graph.api, model.api, common.storage
index      -> common
graph      -> common
model      -> common
bootstrap  -> wires all modules together
maintenance-> module APIs and common
```

## Module catalog and resource ownership

| Module | Responsibility | Synchronous API boundary | Exclusive resources |
| --- | --- | --- | --- |
| `generation` | Answer generation, query planning, spreadsheet calculations, answer cache | `GenerationApi`, `GenerationCacheMaintenanceApi` | Qdrant `semantic_answer_cache_v1`, Redis namespace `generation` |
| `retrieval` | Fusion, reranking, contextual query expansion, neighbor and table completion | `RetrievalApi` | Redis namespace `retrieval` |
| `ingestion` | Text parsing, chunking, graph extraction, worker checkpoints, status events | `IngestionApi`, `DocumentIndexLifecycleApi`, `IngestionCheckpointMaintenanceApi` | RabbitMQ queues, Redis `ccn:v1:ingestion:*` |
| `index` | Dense/sparse vector indexing, document unit listing, table row queries | `KnowledgeIndexCommandApi`, `KnowledgeIndexQueryApi` | Qdrant `knowledge_units_v2` |
| `graph` | Kuzu schema, graph entity projection, relationship traversal | `GraphProjectionApi`, `GraphQueryApi` | Kuzu database, graph-service HTTP |
| `model` | Ollama/Qwen chat adapters, BGE-M3 and BM25 embeddings, token limit handling | `ChatModelApi`, `TextEmbeddingApi`, `SparseEmbeddingApi` | Redis namespace `model` |

## Durable document ingestion

Redis checkpoints track atomic phase transitions:
```text
CLAIMED -> PROCESSING_PUBLISHED -> INDEX_REPLACED -> GRAPH_REPLACED
        -> COMPLETED_PUBLISHED -> COMPLETE
```
Republish incomplete jobs during recovery:
```bash
make -C rag-chatbot-fastapi recover-ingestion-checkpoints
```

## Code placement reference

| Code type | Target location |
| --- | --- |
| Cross-module protocol | `<module>.api` |
| Boundary command / result / enum | `<module>.api` |
| Module-internal application logic | `<module>.internal` |
| Transport mapping (gRPC, HTTP, RabbitMQ) | `<module>.transport` |
| Dependency injection and composition | `bootstrap` |
| Business-neutral technical utilities | `common` |
| Maintenance commands | `maintenance` |

## Architecture enforcement

Run the complete Python verification suite:
```bash
make -C rag-chatbot-fastapi check PYTHON=python3.11
```
`tests/test_architecture.py` inspects AST import trees and rejects any cross-module import violation or resource access leak with zero allowlist exceptions.
