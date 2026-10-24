# OpenVie and the DX-OS (Open-Core) architecture

[Documentation index](README.md) · [Architecture](ARCHITECTURE.md) · [Deployment](DEPLOYMENT.md) · [Open-source boundary](OPEN_SOURCE.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#tiếng-việt) · [English](#english)

---

## Tiếng Việt

Tài liệu này đối chiếu mã nguồn OpenVie với khung kiến trúc **Hệ điều hành Chuyển đổi số (DX-OS)** và mô hình **Open-Core** dùng trong cuộc thi Phần mềm nguồn mở (OLP) 2026. Mọi nhận định được kiểm chứng trực tiếp trên mã nguồn.

### 1. Vị trí trong tháp 3 tầng DX-OS

| Tầng DX-OS | Thành phần trong OpenVie | Trạng thái |
| --- | --- | --- |
| **Tầng 1 – Nền tảng Dịch vụ Lõi số** (PaaS, headless) | `api/` (Spring Boot trên Java 21), `rag-chatbot-fastapi/` (AI HTTP + gRPC + worker + DuckDB Lakehouse), `apisix/` (cổng API), `docker-compose.yml` (Postgres, Redis, RabbitMQ, Qdrant, SeaweedFS, Kuzu) | **Đạt** — đủ các dịch vụ lõi bắt buộc kèm hồ dữ liệu phân tích Lakehouse (Parquet/DuckDB); các thành phần biên nâng cao (WAF/Vault) thuộc tùy chọn hạ tầng |
| **Tầng 2 – Không gian Năng lực số** (H-P-D-I, có giao diện) | `frontend/` (Next.js) | **Đạt** (có ứng dụng minh họa giao diện) |
| **Tầng 3 – Nền tảng Kinh doanh số** (OMS/WMS/POS/LMS/CRM…) | Không có | Ngoài phạm vi |

### 2. Nguyên tắc lõi chạy ngầm (Headless)

- Nền tảng lõi **không cung cấp UI cho người dùng cuối**, hoạt động như một PaaS phục vụ các ứng dụng tầng trên.
- `api/` chỉ xuất HTTP JSON (`/api/v1/**`) và gRPC nội bộ (`:50051`).
- Toàn bộ giao diện người dùng tách biệt hoàn toàn tại `frontend/` (Tầng 2).
- Cổng biên `apisix/` chỉ mở giao diện web và `/api/v1/*`. Các hạ tầng dữ liệu và AI không lộ ra ngoài mạng.
- Kết luận: nguyên tắc lõi headless **Đạt** — không UI ở core, API chỉ HTTP JSON + gRPC nội bộ, biên chỉ lộ web + `/api/v1`.

### 3. Bốn dịch vụ lõi bắt buộc

| Dịch vụ lõi | Hiện trạng kiểm chứng | Trạng thái | Việc cần làm |
| --- | --- | --- | --- |
| **Định danh, SSO** | Keycloak OIDC IdP (Authorization Code + PKCE S256), Spring Boot SSO exchange (`/api/v1/auth/sso/exchange`), mật khẩu + JWT, phân quyền 3 cấp (`ORG_OWNER`, `WORKSPACE_ADMIN`, `MEMBER`), xoay vòng token, API key machine-to-machine (`/api/v1/admin/api-keys`, `ovk_…` xác thực như service member theo membership sống) | **Đạt** | Xem [KEYCLOAK.md](KEYCLOAK.md) và [DEVELOPMENT §5](DEVELOPMENT.md#hướng-dẫn-chạy-và-kiểm-tra-đăng-nhập-sso-cục-bộ-local-sso-run--first-run-checks) |
| **Cổng API (API Gateway)** | Apache APISIX standalone (`:8088`), định tuyến và giới hạn tần suất biên (`limit-req`) | **Đạt** | Xem [DEPLOYMENT §10](DEPLOYMENT.md#10-edge-gateway-apache-apisix) |
| **Quản trị dữ liệu cấu trúc** | PostgreSQL 16 + Flyway (23 bảng nghiệp vụ, ràng buộc tenant_id) | **Đạt** | — |
| **Quản trị dữ liệu phi cấu trúc & Hồ dữ liệu (Lakehouse)** | SeaweedFS (S3), Qdrant (vector), Kuzu (đồ thị tri thức), phân vùng Parquet Lakehouse nén Snappy (chat, audit, document, workflow) kèm cỗ máy truy vấn OLAP nhúng DuckDB (`/api/v1/analytics/lakehouse/**`), hấp thụ đa định dạng (PDF, DOCX, XLSX, CSV, âm thanh, video qua ASR faster-whisper), OCR tùy chọn cho PDF scan/ảnh (tắt theo mặc định) | **Đạt** | — |
| **Động cơ luồng công việc (Workflow)** | Engine Flowable nhúng trong API runtime chính: nộp → duyệt/từ chối/leo thang hai cấp, chính sách độ nhạy dùng chung, SLA theo tổ chức, audit log đầy đủ; API engine tổng quát `/api/v1/workflow/engine` (triển khai BPMN bất kỳ, khởi động process + biến, inbox tác vụ, lịch sử); write-back kết quả duyệt vào bảng `documents` (Flyway V8, `approval_status`), thông báo SMTP (`WORKFLOW_DECIDED`), và đẩy webhook có chữ ký HMAC-SHA256 | **Đạt** | Xem [WORKFLOW.md](WORKFLOW.md), [INTEGRATION.md](INTEGRATION.md) |

**Bề mặt tích hợp cho bên thứ ba** (chi tiết ở [INTEGRATION.md](INTEGRATION.md)): API key machine-to-machine
(`ovk_…`, xác thực như service member qua membership sống), webhook sự kiện có chữ ký HMAC-SHA256
(`document.status.changed`, `workflow.*`), chế độ chat đồng bộ (`Accept: application/json`), và API engine
BPMN tổng quát — mỗi thứ đều có kill-switch cấu hình (`app.webhooks.enabled`, `app.rate-limit.*`).

### 4. Đánh nhãn H-P-D-I cho giao diện Tầng 2

- **[I] Trí tuệ số:** Trợ lý ảo RAG vận hành trên kho tri thức nội bộ (`/`, `/documents/[id]`) — **Đạt** (ứng dụng minh họa chính).
- **[H] Con người số:** Kho tài liệu, bách khoa hướng dẫn, quản trị thành viên/workspace (`/documents`, `/documentation`, `/users`, `/workspaces`) — **Đạt**.
- **[P] Quy trình số:** Phê duyệt tài liệu hai cấp chạy trong runtime chính: nộp → duyệt/từ chối/leo thang, chính sách độ nhạy dùng chung, SLA nhắc nhở/leo thang, audit log; ghi nhận trạng thái phê duyệt trực tiếp vào tài liệu (`approval_status`), thông báo email qua kênh SMTP và đẩy webhook sự kiện cho bên thứ ba — **Đạt**.
- **[D] Dữ liệu số:** Nguồn gốc tài liệu, nhật ký kiểm toán, số liệu Prometheus + Alertmanager kèm 2 dashboard Grafana chuyên biệt (tổng quan nền tảng và AI/hấp thụ dữ liệu), công cụ sao lưu và phục hồi cơ sở dữ liệu/lưu trữ đối tượng (`scripts/ops/`) — **Đạt (MVP)**.

### 5. Bốn nguyên lý thiết kế

1. **(i) Phân tách vòng đời & tốc độ dữ liệu:** **Đạt (Lakehouse)** — Tách bạch hoàn toàn dữ liệu giao dịch tác nghiệp thời gian thực (PostgreSQL OLTP) khỏi dữ liệu lịch sử và phân tích tổng hợp (hồ dữ liệu Parquet lưu trên SeaweedFS S3, truy vấn bằng động cơ OLAP nhúng DuckDB). Luồng CDC truyền phát sự kiện bất đồng bộ qua RabbitMQ sang Lakehouse mà không gây nghẽn hệ thống lõi.
2. **(ii) Hội tụ dữ liệu IT và OT:** **Không áp dụng (Pure IT/e-Office Scope)** — Đề thi định hướng nguyên lý này cho các hệ thống nhà máy, đô thị thông minh có cảm biến hiện trường (IoT/SCADA/BMS). OpenVie định vị chuyên sâu vào không gian số hóa quản trị văn phòng (e-Office), quản trị tri thức nội bộ và trợ lý AI, nên dữ liệu vận hành công nghiệp (OT) được phân định rõ ràng là ngoài phạm vi của bài toán sản phẩm.
3. **(iii) Quản trị dữ liệu thế hệ mới:** **Đạt** — Đảm bảo kiểm soát toàn diện nguồn gốc dữ liệu và phả hệ xuyên suốt: sơ đồ Data Lineage DAG trực quan hóa luồng dữ liệu (`/api/v1/analytics/lakehouse/lineage/documents/{id}`), kiểm soát chất lượng dữ liệu tự động (Data Quality Framework `/api/v1/analytics/lakehouse/quality` với các assertion về completeness, uniqueness, freshness, schema validity), theo dõi phả hệ văn bản (provenance hash SHA-256, uploader, revision, timestamp), liên kết dẫn chứng ngược về chính xác tọa độ trang và ô bảng tính (evidence anchoring), nhật ký kiểm toán bất biến (audit log), cơ chế giao dịch Transactional Outbox/Inbox chống thất thoát, và luồng CDC tự động đẩy bản ghi sang hồ dữ liệu Parquet.
4. **(iv) Zero-Trust & GitOps:** **Đạt** — Bảo mật không tín nhiệm ở mọi tầng kết hợp tự động hóa vận hành và tự phục hồi tức thì: Trạm kiểm dịch mã độc ClamAV quét trực tiếp luồng byte tệp tải lên (INSTREAM protocol và nhận diện EICAR), cách ly mã độc ngay tại cửa ngõ biên mạng; APISIX edge gateway giới hạn tần suất chống brute-force, lọc tấn công WAF (`uri-blocker`) và bổ sung tiêu đề an ninh (Security Headers: nosniff, SAMEORIGIN, HSTS); JWT xoay vòng, API key máy (M2M) tuân thủ RBAC 3 cấp, mTLS cho gRPC nội bộ; quy trình GitOps tự động hóa (`scripts/ops/gitops_sync.sh`), cơ chế tự phục hồi tức thì qua Docker Autoheal daemon (`autoheal:1.2.0`), và bộ công cụ diễn tập phục hồi thảm họa tự động (`scripts/ops/disaster_recovery_drill.sh`).

### 6. Bảy lớp công nghệ tham chiếu

*(Lưu ý: Mục 4 đề thi khẳng định các đội thi có toàn quyền lựa chọn công nghệ phù hợp, bảng 7 lớp là giải pháp tham chiếu minh họa. Dưới đây là đối chiếu theo đúng vai trò kiến trúc và giá trị vận hành):*

- **Lớp 1 (Trải nghiệm & Giao diện Đa kênh):** Next.js, React, Tailwind CSS (**Đạt**) — Bóc tách giao diện hoàn toàn khỏi lõi headless qua API Gateway APISIX; duy trì truyền phát trạng thái thời gian thực qua Server-Sent Events (SSE) và WebSocket.
- **Lớp 2 (Biên mạng, Cửa ngõ & An toàn thông tin):** Apache APISIX, Keycloak OIDC IdP (PKCE S256), Spring Security JWT (**Đạt - MVP**) — Lưới bảo mật Zero-Trust biên mạng, SSO tập trung, rate-limiting chống tấn công từ chối dịch vụ và brute-force, quản lý phân quyền 3 cấp (`ORG_OWNER`, `WORKSPACE_ADMIN`, `MEMBER`).
- **Lớp 3 (Tác nghiệp, Logic & Trạng thái Thực thể):** PostgreSQL 16 (ACID OLTP), Kuzu Graph Service (đồ thị ngữ cảnh thực thể), RabbitMQ, gRPC (**Đạt**) — Xử lý giao dịch tác nghiệp liên tục, nhất quán, bảo đảm toàn vẹn dữ liệu đa thuê ước (`tenant_id`); duy trì và truy vấn đồ thị quan hệ ngữ cảnh thực thể.
- **Lớp 4 (Tự động hóa, Quy trình & DevSecOps):** Flowable BPMN 2.0 nhúng, GitHub Actions CI, Shell Ops Scripts (`scripts/ops/`) (**Đạt**) — Số hóa quy trình phê duyệt hồ sơ văn bản e-Office 2 cấp với SLA tự động leo thang; triển khai hạ tầng tự động bằng mã (Docker Compose); bộ công cụ sao lưu và phục hồi thảm họa kiểm chứng toàn vẹn SHA-256.
- **Lớp 5 (Dữ liệu Siêu hội tụ, Truyền phát & Sổ cái):** RabbitMQ (trục truyền phát sự kiện CDC), SeaweedFS S3 (kho hồ dữ liệu Parquet phân vùng theo tenant/năm/tháng) (**Đạt - MVP**) — Trục truyền phát sự kiện tốc độ cao chống chối bỏ, lưu trữ hồ dữ liệu Parquet nén Snappy lâu dài phục vụ phân tích.
- **Lớp 6 (Phục vụ Phân tích, Không gian & Trí tuệ):** DuckDB (cỗ máy truy vấn phân tích OLAP trực tiếp trên Parquet), Ollama (`vylinh` + `bge-m3`), Qdrant, Kuzu (**Đạt - MVP**) — Thực thi truy vấn phân tích SQL tổng hợp siêu tốc không nghẽn database lõi; triển khai các mô hình AI/LLM tự hành và bảo mật hoàn toàn trong nội bộ.
- **Lớp 7 (Quản trị, Tìm kiếm & Giám sát Hệ thống):** Qdrant (tìm kiếm ngữ nghĩa vector), Prometheus metrics, Alertmanager, 2 dashboard Grafana provision tự động theo dõi nền tảng và AI (`monitoring/`) (**Đạt - MVP**) — Giám sát 360 độ hệ thống và AI, hỗ trợ người dùng tra cứu thông tin chính xác.

---

## English

This document evaluates OpenVie against the **Digital Transformation Operating System (DX-OS)** architecture and **Open-Core** competition guidelines (OLP 2026).

### 1. Position in the DX-OS 3-Tier Pyramid

| DX-OS Tier | OpenVie Component | Status |
| --- | --- | --- |
| **Tier 1 – Digital Core Platform** (Headless PaaS) | `api/` (Spring Boot on Java 21), `rag-chatbot-fastapi/` (AI inference, gRPC, worker, DuckDB Lakehouse), `apisix/` (gateway), `docker-compose.yml` (Postgres, Redis, RabbitMQ, Qdrant, SeaweedFS, Kuzu) | **Achieved** — all mandatory core services delivered alongside Lakehouse analytical data store (Parquet/DuckDB); perimeter edge hardening (WAF/Vault) remains an infrastructure operator option |
| **Tier 2 – Digital Capability Space** (H-P-D-I with UI) | `frontend/` (Next.js) | **Achieved** (UI demo application) |
| **Tier 3 – Digital Business Platform** (OMS, WMS, POS, LMS...) | None | Out of scope |

### 2. Headless Core Principle

- The core platform **does not provide end-user UI**; it functions as a PaaS providing services for upper-tier applications.
- `api/` only exposes REST endpoints (`/api/v1/**`) and internal gRPC (`:50051`).
- The entire web UI lives in `frontend/` (Tier 2).
- Edge gateway `apisix/` only publishes the web client and `/api/v1/*`. Internal data and AI services are never exposed.
- Verdict: the headless core principle is **Achieved** — no UI in the core, JSON-over-HTTP plus internal gRPC only, and the edge exposes nothing beyond the web client and `/api/v1`.

### 3. Four Mandatory Core Services

| Core Service | Delivered Implementation | Status | Roadmap |
| --- | --- | --- | --- |
| **Identity & SSO** | Keycloak OIDC IdP (Authorization Code flow + PKCE S256), Spring Boot SSO token exchange (`/api/v1/auth/sso/exchange`), password + JWT, 3-role RBAC (`ORG_OWNER`, `WORKSPACE_ADMIN`, `MEMBER`), token rotation, machine-to-machine API keys (`/api/v1/admin/api-keys`, `ovk_…` authenticating as a service member through live membership) | **Achieved** | See [KEYCLOAK.md](KEYCLOAK.md) and [DEVELOPMENT §5](DEVELOPMENT.md#local-sso-run--first-run-verification-guide) |
| **API Gateway** | Apache APISIX standalone (`:8088`), route proxying, and edge rate-limiting (`limit-req`) | **Achieved** | See [DEPLOYMENT §10](DEPLOYMENT.md#10-edge-gateway-apache-apisix) |
| **Structured Data** | PostgreSQL 16 + Flyway (23 business tables with tenant scoping) | **Achieved** | — |
| **Unstructured & Lakehouse Data** | SeaweedFS (S3), Qdrant (vectors), Kuzu (knowledge graph), partitioned Snappy Parquet Lakehouse (chat, audit, document, workflow) with embedded DuckDB OLAP engine (`/api/v1/analytics/lakehouse/**`), asynchronous multi-format ingestion (PDF, DOCX, XLSX, CSV, audio and video via faster-whisper ASR), optional OCR for scanned PDFs/images (off by default) | **Achieved** | — |
| **Workflow Engine** | Flowable embedded in the main API runtime: two-level submit → approve/reject/escalate, shared sensitivity policy, per-organization SLA, full audit trail; generic engine API `/api/v1/workflow/engine` (deploy any BPMN definition, start processes with variables, generic task inbox, history); decision write-back directly onto the `documents` row (Flyway V8, `approval_status`), transactional SMTP notification (`WORKFLOW_DECIDED`), and signed HMAC-SHA256 webhook dispatch | **Achieved** | See [WORKFLOW.md](WORKFLOW.md), [INTEGRATION.md](INTEGRATION.md) |

**Third-party integration surface** (details in [INTEGRATION.md](INTEGRATION.md)): machine-to-machine API keys
(`ovk_…` authenticating as a service member through live membership), HMAC-SHA256-signed event webhooks
(`document.status.changed`, `workflow.*`), a synchronous chat mode (`Accept: application/json`), and the
generic BPMN engine API — each with a configuration kill-switch (`app.webhooks.enabled`, `app.rate-limit.*`).

### 4. H-P-D-I Capability Labeling (Tier 2)

- **[I] Digital Intelligence:** Document-grounded RAG assistant (`/`, `/documents/[id]`) — **Achieved** (primary demo).
- **[H] Digital Human:** Document vault, documentation wiki, workspace/member management (`/documents`, `/documentation`, `/users`, `/workspaces`) — **Achieved**.
- **[P] Digital Process:** Two-level document approval in the main runtime: submit → approve/reject/escalate, shared sensitivity policy, SLA reminders/escalation, audit logging; authoritative approval state written directly onto document records (`approval_status`), automated SMTP notification dispatch, and signed webhook event publication — **Achieved**.
- **[D] Digital Data:** Source provenance, audit logs, Prometheus + Alertmanager metrics with 2 provisioned Grafana dashboards (platform overview and AI/ingestion), verifiable backup/restore tooling (`scripts/ops/`) — **Achieved (MVP)**.

### 5. Four Design Principles

1. **(i) Lifecycle & Velocity Separation:** **Achieved (Lakehouse)** — Clean architectural separation between real-time transactional data (PostgreSQL OLTP) and cold historical analytical archives (Parquet partitions on SeaweedFS S3 queried via embedded DuckDB OLAP). Asynchronous CDC streaming over RabbitMQ keeps analytical load off the core database.
2. **(ii) IT and OT Convergence:** **Not Applicable (Pure IT/e-Office Scope)** — The competition guidelines target this principle at manufacturing or smart-city IoT/SCADA sensor platforms. OpenVie is strictly positioned as an office digitalization (e-Office), knowledge management, and AI assistant operating system; industrial physical sensor data (OT) is deliberately out of product scope.
3. **(iii) Next-Gen Data Governance:** **Achieved** — Comprehensive data origin, provenance, and lineage tracking: end-to-end Data Lineage DAG API (`/api/v1/analytics/lakehouse/lineage/documents/{id}`), automated Data Quality assertions (`/api/v1/analytics/lakehouse/quality` covering completeness, uniqueness, freshness, and schema validity), cryptographic document hash (SHA-256), visual evidence anchoring linking back to exact page bounding boxes and spreadsheet cells, immutable audit logging, transactional outbox/inbox queues, and automated CDC lakehouse streaming.
4. **(iv) Zero-Trust & GitOps:** **Achieved** — Zero-trust defense-in-depth coupled with automated operations and instant self-recovery: ClamAV edge quarantine scanning uploaded byte streams (INSTREAM protocol and built-in EICAR detection), rejecting and quarantining malware before ingestion; APISIX edge gateway with brute-force rate-limiting, WAF URI-blocking filters, and strict security headers (nosniff, SAMEORIGIN, HSTS); rotating JWT tokens, 3-tier RBAC machine-to-machine API keys, mTLS internal gRPC; declarative GitOps reconciliation (`scripts/ops/gitops_sync.sh`), instant automated self-healing via Docker Autoheal daemon (`autoheal:1.2.0`), and automated disaster recovery verification drills (`scripts/ops/disaster_recovery_drill.sh`).
### 6. Seven Reference Technology Layers
*(Note: Section 4 of the competition guidelines explicitly grants teams full freedom of technology choice; the 7-layer reference stack is an illustrative example. Evaluation is mapped against technical architecture roles and operational value):*

- **Layer 1 (Experience & Multi-Channel UI):** Next.js, React, Tailwind CSS (**Achieved**) — Full headless decoupled web interface via Apache APISIX edge gateway; real-time event streaming via SSE and WebSocket.
- **Layer 2 (Perimeter, Edge & Security):** Apache APISIX, Keycloak OIDC IdP (PKCE S256), Spring Security JWT (**Achieved - MVP**) — Perimeter Zero-Trust security mesh, centralized SSO, rate-limiting, 3-tier RBAC (`ORG_OWNER`, `WORKSPACE_ADMIN`, `MEMBER`).
- **Layer 3 (Operations, Logic & Entity State):** PostgreSQL 16 (ACID OLTP), Kuzu Graph Service (entity context graph), RabbitMQ, gRPC (**Achieved**) — Continuous transactional consistency with tenant isolation (`tenant_id`); real-time entity relation graph queries.
- **Layer 4 (Automation, Process & DevSecOps):** Embedded Flowable BPMN 2.0, GitHub Actions CI, Shell Ops Scripts (`scripts/ops/`) (**Achieved**) — Two-level document approval workflow with automated SLA escalation; infrastructure as code (Docker Compose); sha256-verified disaster recovery backup and restore tooling.
- **Layer 5 (Hyperconverged Data & Ledger):** RabbitMQ (CDC streaming event bus), SeaweedFS S3 (partitioned Snappy Parquet Lakehouse archive) (**Achieved - MVP**) — High-speed non-repudiation event streaming and durable columnar lakehouse storage.
- **Layer 6 (Analytics, Space & Intelligence):** DuckDB (embedded OLAP query engine over Parquet), Ollama (`vylinh` + `bge-m3`), Qdrant, Kuzu (**Achieved - MVP**) — Distributed SQL aggregate queries with zero OLTP database lock contention; on-premise sovereign AI models.
- **Layer 7 (Governance, Search & Monitoring):** Qdrant vector search, Prometheus metrics, Alertmanager, 2 provisioned Grafana dashboards (`monitoring/`) (**Achieved - MVP**) — 360-degree observability and semantic search.
