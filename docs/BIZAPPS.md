# BizApps — nền tảng ứng dụng kinh doanh cài được (Tier 3 / v1.0.0 GA)

[Danh mục tài liệu](README.md) · [Kiến trúc](ARCHITECTURE.md) · [Tích hợp](INTEGRATION.md) · [Workflow](WORKFLOW.md) · [DX-OS](DX_OS.md) · [Nguồn mở & Cấp phép](OPEN_SOURCE.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#bizapps--nền-tảng-ứng-dụng-kinh-doanh--tiếng-việt) · [English](#bizapps--the-installable-business-app-platform)

---

## BizApps — nền tảng ứng dụng kinh doanh — tiếng Việt

BizApp là **ứng dụng kinh doanh khai báo** cài được vào một workspace thuộc DX-OS Tầng 3,
chạy trực tiếp trên các headless service sẵn có của nền tảng mà không cần backend riêng:
kho bản ghi hybrid (lưu trữ typed table cho các app platform và JSONB cho app tự tạo),
phê duyệt quy trình qua Flowable engine có SLA timers, gửi thông báo email, webhook sự kiện
HMAC-SHA256, HTTP outbound được bảo vệ chống SSRF, và kho thông tin xác thực mã hóa AES-256-GCM.
Mẫu đóng gói sẵn bao gồm:
1. **CRM Lite** (quản lý khách hàng tiềm năng, lưu trữ typed table `bizapp_crm_leads`),
2. **Booking** (đặt lịch hẹn có xác nhận phê duyệt, lưu trữ typed table `bizapp_booking_appointments`),
3. **Signature Request** (công thức yêu cầu ký số ủy quyền qua nhà cung cấp bên ngoài).

### 1. Hợp đồng Manifest `bizapp.v1` đóng băng (Frozen Contract)

Mọi BizApp được định nghĩa bởi một tài liệu JSON chuẩn `bizapp.v1` (schema tại `contracts/bizapp/bizapp.v1.schema.json`):

| Trường | Ý nghĩa |
| --- | --- |
| `schema` | Luôn là `"bizapp.v1"` |
| `kind` | Mã định danh duy nhất của app (`[a-z][a-z0-9-]{1,31}`) |
| `name` | Đối tượng bản địa hóa: `{"vi": "...", "en": "..."}` |
| `version` | Số nguyên phiên bản (>= 1) |
| `storage` | `"jsonb"` (mặc định) hoặc `"typed"` (chỉ dành riêng cho platform-bundled templates) |
| `entities` | Khai báo các thực thể dữ liệu (tối đa 20 thực thể, 40 trường/thực thể): `fields[]` (`string`, `number`, `boolean`, `enum`, `date`, `ref`), `knowledge` projection, và `hooks` |
| `blocks` | Khối thực thi (tối đa 40 khối): `approval_workitem`, `assistant`, `knowledge_base`, `report`, `notify`, `webhook_out`, `external_call` |
| `rbac` | Quyền hạn `read/write/delete/approve` gắn với các vai trò (`ORG_OWNER`, `WORKSPACE_ADMIN`, `MEMBER`) |
| `ui` | Thứ tự các view runtime (`registry:<entity>`, `form:<entity>`, `timeline:<entity>`, `report:<view>`, `assistant:<block>`) cùng tọa độ bố cục `layout` của visual composer |

**Báo cáo khai báo (Declarative Reports — Thay thế hoàn toàn raw SQL):**
Báo cáo không chứa chuỗi SQL tự do; thay vào đó định nghĩa dưới dạng view khai báo an toàn:
```json
{
  "name": "pipeline_by_stage",
  "entity": "leads",
  "groupBy": ["stage"],
  "metrics": [
    { "fn": "count", "as": "leads" },
    { "fn": "sum", "field": "dealValue", "as": "value" }
  ],
  "filters": [
    { "field": "stage", "op": "in", "values": ["NEW", "CONTACTED"] }
  ],
  "orderBy": [
    { "by": "leads", "dir": "desc" }
  ],
  "limit": 100,
  "engine": "postgres"
}
```
Trình biên dịch `BizAppReportCompiler` phân tích view spec thành câu lệnh tham số hóa có ràng buộc tenant/org/app nghiêm ngặt, hỗ trợ cả hai dialect `postgres` và `duckdb` (Lakehouse).

**Xuất dữ liệu (CSV / Excel):** registry của entity và report view đều tải được toàn bộ kết quả (không chỉ trang đang hiển thị):
- `GET /api/v1/bizapps/{kind}/entities/{entity}/export?format=csv|xlsx&locale=vi|en` — toàn bộ bản ghi đang hoạt động theo thứ tự khai báo trường (nhãn vi/en, fallback tên trường) cộng `ID`/trạng thái/thời điểm tạo, cập nhật; chặn ở 10 000 bản ghi.
- `POST /api/v1/bizapps/{kind}/reports/{viewName}/export?format=csv|xlsx` — mọi cột và mọi dòng view trả về; chỉ workspace admin.
- CSV là UTF-8 có BOM (Excel đọc đúng tiếng Việt), mọi ô được quote, ô bắt đầu bằng `= + - @` được thêm `'` để chặn formula injection; XLSX ghi bằng Apache POI `SXSSF` và giữ ô số đúng kiểu số.

### 2. Chiếu tri thức (Knowledge Projection: Bản ghi → Trợ lý AI)

Nhằm cho phép trợ lý ảo trả lời câu hỏi về dữ liệu kinh doanh **kèm trích dẫn chính xác nguồn dữ liệu** mà không can thiệp vào tầng truy hồi (retrieval) và không dùng Text-to-SQL:
- Thực thể bật chiếu qua `knowledge: { "project": true, "titleField": "name", "fields": ["name", "stage"] }`.
- Mỗi bản ghi được chiếu thành một tài liệu Markdown tổng hợp (`bizapp_<kind>_<entity>_<recordId>.md`) đưa vào pipeline hấp thụ tự động của Qdrant và đồ thị tri thức Kuzu.
- **Quy tắc fail-closed:** Chiếu tri thức chỉ được phép kích hoạt khi `rbac.read` bao gồm đầy đủ mọi vai trò trong workspace (`ORG_OWNER`, `WORKSPACE_ADMIN`, `MEMBER`). Trường đánh dấu `pii: true` không bao giờ bị chiếu.
- **Tài liệu tổng hợp (Digest Document):** `bizapp_<kind>__digest.md` tự động kết xuất từ các report view đã khai báo để trả lời câu hỏi tổng hợp mang tính định lượng chính xác.

### 3. Trình thiết kế trực quan (Visual Composer)

- Giao diện kéo thả tại `(admin)/admin/apps/compose`:
  - **Canvas trực quan:** Sử dụng `@xyflow/react` kết hợp thuật toán sắp xếp tự động `@dagrejs/dagre`, hiển thị thực thể (`NodeEntity`) và khối (`NodeBlock`).
  - **Dạng danh sách (List View):** Cung cấp giao diện tương tác toàn diện bằng bàn phím và trợ năng (accessibility scan đạt chuẩn axe).
  - **Trình sửa JSON (Json Tab):** Cho phép xem và chỉnh sửa trực tiếp mã JSON canonical `bizapp.v1`.
  - **Kiểm tra tự động (Validation Panel):** Kiểm tra hợp lệ debounced qua `POST /api/v1/bizapps/validate`.

### 4. Soạn thảo Manifest bằng AI (AI Manifest Drafting)

- Endpoint `POST /api/v1/bizapps/drafts/generate` (chỉ `ORG_OWNER`, có rate-limiting và kill-switch `app.bizapps.ai-drafting.enabled`).
- Không bao giờ tự động cài đặt: kết quả sinh ra chỉ lưu vào bảng `bizapp_drafts` (Flyway V14) dưới dạng bản nháp để người dùng xem lại. Khi nhấn cài đặt, hệ thống sẽ thẩm định lại từ đầu.
- Bộ prompt hệ thống độc lập kèm bộ kiểm thử chất lượng 20 kịch bản tại `contracts/bizapp/eval/drafting-prompts.jsonl`.

### 5. Trao đổi Template (Export / Import / Remix)

Mọi template đóng gói và trao đổi đều sử dụng phong bì tiêu chuẩn `openvie.template.v1` (`contracts/bizapp/openvie.template.v1.schema.json`):
```json
{
  "envelope": "openvie.template.v1",
  "meta": {
    "author": "LinkedNodeDigital",
    "license": "Apache-2.0",
    "source": "bundled",
    "sha256": "...",
    "generator": "human",
    "changes": []
  },
  "manifest": { ... }
}
```
- Endpoint:
  - `GET /api/v1/marketplace/bizapps/{id}/export`: Tải file phong bì JSON.
  - `POST /api/v1/marketplace/bizapps/import`: Nhập template từ file hoặc URL catalog, kiểm tra mã băm SHA-256 và danh sách giấy phép SPDX cho phép; chỉ đưa vào danh sách bản nháp.
  - `POST /api/v1/marketplace/bizapps/{id}/remix`: Sao chép sang bản nháp, giữ nguyên tác giả/giấy phép gốc và bổ sung mục lịch sử sửa đổi `changes[]`.
  - `GET /api/v1/marketplace/catalog`: Lấy chỉ mục catalog từ các host được cấu hình trong `app.bizapps.remote-catalog.hosts`.

### 6. Khối gọi ngoài an toàn & Công thức Ký số (e-Signature Recipe)

Khối `external_call` là lớp connector khai báo (HTTP/HTTPS + MQTT):
- Mỗi lần chạy là một job bền vững (`bizapp_connector_jobs`) chạy sau khi giao dịch ghi bản ghi commit; retry lũy thừa (mặc định 3 lần, backoff `PT10S`, tối đa 10 phút), mỗi lần thử được audit (`BIZAPP_EXTERNAL_CALL`) và đo bằng metric `bizapp_connector_runs_total` / `bizapp_connector_duration`.
- HTTP/HTTPS: chỉ HTTPS (trừ dev `app.bizapps.outbound.allow-http`); host phải nằm trong `allowedHosts` của installation, `egress.allowedHosts` (nếu khai báo) chỉ thu hẹp thêm. DNS/SSRF, không redirect, phản hồi tối đa 256 KiB, log đã khử query/header nhạy cảm.
- MQTT: `brokerUrl` dạng `ssl://` (hoặc `tcp://` khi bật `app.bizapps.outbound.allow-http`), subscribe có giới hạn `maxMessages`, timeout `readMs`, luôn disconnect sau mỗi job.
- Xác thực: vault header mặc định (`api_key`, `bearer`, `basic`), `auth.type=hmac` ký `X-OpenVie-Timestamp` + `X-OpenVie-Signature`, `auth.type=oauth2` dùng client-credentials với token cache 80% TTL. Credential có thể scope theo workspace.
- Tiêu đề chống gửi trùng lặp: `X-Idempotency-Key: <jobId>`; `responseMap` JSON-pointer ghi vào `runtime.external.<blockId>`.
- Sink: `field` cập nhật bản ghi hiện tại; `record` append/upsert vào entity đích theo `dedupeKey` (unique index), nên poll lặp không nhân bản telemetry.
- `schedule` (`every` 30s..7d, `select`, `limit`) do scheduler quét và enqueue; bản ghi có job PENDING/RUNNING hoặc vừa SUCCEEDED trong `every` sẽ được bỏ qua.

### 7. Ranh giới pháp lý (Legal Boundaries)

> **Tuyên bố ranh giới pháp lý:**
> OpenVie là nền tảng phần mềm mã nguồn mở. OpenVie **không** tạo, không nắm giữ khóa ký riêng tư (private key), và không thực hiện quy trình ký số hay kiểm tra tính hợp lệ pháp lý của chứng thư điện tử. Mọi tác vụ ký số được thực hiện bằng cách ủy quyền thông qua khối `external_call` tới nhà cung cấp dịch vụ ký số bên ngoài do tổ chức tự hợp đồng và cấu hình.
> Giá trị pháp lý của văn bản ký kết phụ thuộc hoàn toàn vào dịch vụ của nhà cung cấp đó và các quy định pháp luật hiện hành tại quốc gia sở tại. Người vận hành hệ thống có trách nhiệm tự tham vấn ý kiến pháp lý chuyên môn cho các hoạt động ký kết điện tử của mình.
### 8. Trợ lý ảo & Ứng dụng (Conversational BizApps)

Từ phiên bản `v1.0.0`, trợ lý ảo có khả năng nhận biết ứng dụng, gợi ý cài đặt, đề xuất thẻ hành động, và tạo ứng dụng mới từ lịch sử hội thoại:

1. **Ba tầng tách biệt (Three-tier Architecture):**
   - **Tầng A (Deterministic):** Liệt kê ứng dụng đã cài, thông tin chi tiết, thực thi hành động cài/gỡ có xác nhận an toàn. Không gọi model, không chạm KB.
   - **Tầng B (Structured LLM):** Phân loại ý định người dùng (`LIST_APPS`, `APP_DETAIL`, `APP_DATA`, `INSTALL`, `UNINSTALL`, `SUGGEST`, `CREATE_APP`) qua RPC `CompleteJson` riêng có schema nghiêm ngặt.
   - **Tầng C (Document RAG):** Trả lời câu hỏi tài liệu nguyên trạng, được bảo vệ tuyệt đối khỏi ô nhiễm dữ liệu hệ thống.

2. **Thẻ hành động tương tác (Action Cards):**
   - Được lưu tại cột `chat_messages.action` (`jsonb`).
   - Trạng thái vòng đời: `PROPOSED -> EXECUTING -> EXECUTED | FAILED | DENIED`.
   - Điểm thực thi server-authoritative: `POST /api/v1/chat/actions/execute`. Quyền `ORG_OWNER` được kiểm tra nghiêm ngặt phía server. Tham số thực thi đọc lại từ cơ sở dữ liệu theo `messageId` và `actionId`, tuyệt đối không tin cậy dữ liệu do client hoặc LLM gửi lên.

3. **Tạo ứng dụng từ lịch sử chat:**
   - Endpoint `POST /api/v1/bizapps/drafts/from-chat` (`ORG_OWNER`): trích xuất các lượt chat gần nhất từ `ChatRuntimeApi.listRecentTurns`, gọi `CompleteJson` sinh bản thảo manifest `bizapp.v1`, kiểm định qua `BizAppManifestValidator` và lưu vào `bizapp_drafts`.
   - Không bao giờ tự động cài đặt. Người dùng có thể chỉnh sửa trên Visual Composer hoặc bấm nút cài đặt sau khi kiểm tra kỹ.

### 9. Nguồn gốc tài liệu & Quy tắc truy cập an toàn

1. **Cột nguồn gốc `document_kind` (`USER`, `BIZAPP_RECORD`, `BIZAPP_DIGEST`):**
   - Mọi tài liệu tải lên bởi người dùng mang loại `USER`.
   - Các bản ghi được chiếu mang loại `BIZAPP_RECORD` với `source_ref = "bizapp:<kind>"`.
   - Các báo cáo tổng hợp mang loại `BIZAPP_DIGEST`.
2. **Quy tắc an toàn bất biến:**
   - **Ẩn mặc định trong UI:** Trang tài liệu chỉ hiển thị tài liệu người dùng (`include_system=false`). Chỉ `ORG_OWNER` mới có thể xem tài liệu hệ thống (`include_system=true`) cho mục đích kiểm toán.
   - **Không thể xoá bởi người dùng:** Mọi yêu cầu xoá tài liệu hệ thống từ người dùng đều bị chặn với mã lỗi `403 Forbidden` (`AccessDeniedException`).
   - **Không trích dẫn nhầm:** Mọi truy vấn tài liệu thường (`intent = NONE`) tự động loại trừ `BIZAPP_RECORD` và `BIZAPP_DIGEST` ở cả Qdrant (dense/sparse) và Kuzu (graph). Chỉ khi người dùng hỏi đích danh dữ liệu ứng dụng (`intent = APP_DATA`), phạm vi mới được mở có chủ đích.
   - **Tên phẳng duy nhất:** Tài liệu hệ thống sử dụng định dạng phẳng `bizapp__<kind>__<entity>__<id>.md` và `bizapp__<kind>__digest.md`, khắc phục hoàn toàn rủi ro ghi đè basename chéo ứng dụng.

### 10. Giới hạn gộp Digest & Ngữ nghĩa Revision

- **Gộp dao động (Debounce):** `refreshDigest` được gộp với cửa sổ 30 giây (`app.bizapps.projection-debounce-ms=30000`) cho mỗi `(tenant, kind)`. Nhiều thay đổi liên tiếp chỉ kích hoạt một lần tạo digest sau khi kết thúc cửa sổ.
- **Bảo vệ Revision:** Cài đặt và gỡ bỏ ứng dụng **không** ghi vào Knowledge Base, do đó **không** làm tăng `search_revision` và không thể gây lỗi `409 KNOWLEDGE_BASE_CHANGED` cho các lượt chat đang phát trực tiếp.
- **Metric quan sát:** `chat_turn_revision_conflicts_total`, `chat_action_execute_total{action,outcome}`, `chat_app_intent_total{intent}`.

### 11. Các mục tiêu cố ý nằm ngoài phạm vi (Non-Goals)

- Không tích hợp thư viện ký số nhúng hay giữ private key trong tiến trình.
- Không tạo endpoint webhook riêng biệt cho từng nhà cung cấp ký số.
- Không tạo bảng cơ sở dữ liệu động từ manifest của người dùng (chỉ các template platform cốt lõi mới có bảng typed table).
- Không sử dụng Text-to-SQL hay truy vấn do LLM tự sinh.
- Không tự động cài đặt ứng dụng mà không có nút bấm xác nhận từ người dùng.
- Không xác thực chữ ký mật mã detached cho remote template (v1.0.0 neo kiểm tra bằng mã băm SHA-256 từ catalog index).
---

## BizApps — the installable business app platform

A BizApp is a **declarative, installable business application** operating on DX-OS Tier 3,
running directly on the platform's headless services without requiring a dedicated backend:
a hybrid record store (typed SQL tables for platform flagship templates and JSONB store for custom apps),
business approval workflows via the embedded Flowable engine with SLA boundary timers, transactional email,
HMAC-SHA256 signed webhooks, SSRF-protected outbound HTTP, and an AES-256-GCM credential vault.
Bundled flagship templates include:
1. **CRM Lite** (lead pipeline management, backed by typed table `bizapp_crm_leads`),
2. **Booking** (appointment scheduling with confirmation approval, backed by typed table `bizapp_booking_appointments`),
3. **Signature Request** (e-signature delegation recipe template calling external providers).

### 1. Frozen `bizapp.v1` Manifest Contract

Every BizApp is defined by a canonical JSON document (`schema: "bizapp.v1"`, specification at `contracts/bizapp/bizapp.v1.schema.json`):

| Section | Meaning |
| --- | --- |
| `schema` | Must be `"bizapp.v1"` |
| `kind` | Stable unique app key (`[a-z][a-z0-9-]{1,31}`) |
| `name` | Localized label object: `{"vi": "...", "en": "..."}` |
| `version` | Manifest schema revision integer (>= 1) |
| `storage` | `"jsonb"` (default) or `"typed"` (reserved for platform-bundled templates) |
| `entities` | Data models (up to 20 entities, 40 fields/entity): `fields[]` (`string`, `number`, `boolean`, `enum`, `date`, `ref`), `knowledge` projection, and `hooks` |
| `blocks` | Executable blocks (up to 40 blocks): `approval_workitem`, `assistant`, `knowledge_base`, `report`, `notify`, `webhook_out`, `external_call` |
| `rbac` | Role permissions (`read/write/delete/approve`) mapped to workspace roles (`ORG_OWNER`, `WORKSPACE_ADMIN`, `MEMBER`) |
| `ui` | Runtime views order (`registry:<entity>`, `form:<entity>`, `timeline:<entity>`, `report:<view>`, `assistant:<block>`) and visual composer `layout` coordinates |

**Declarative Report Views (Replaces Free-Form SQL):**
Report blocks no longer contain raw SQL strings; they are structured declarative views:
```json
{
  "name": "pipeline_by_stage",
  "entity": "leads",
  "groupBy": ["stage"],
  "metrics": [
    { "fn": "count", "as": "leads" },
    { "fn": "sum", "field": "dealValue", "as": "value" }
  ],
  "filters": [
    { "field": "stage", "op": "in", "values": ["NEW", "CONTACTED"] }
  ],
  "orderBy": [
    { "by": "leads", "dir": "desc" }
  ],
  "limit": 100,
  "engine": "postgres"
}
```
The parameterized `BizAppReportCompiler` compiles view specifications with strict tenant, organization, and entity bounds, supporting both `postgres` and `duckdb` (Lakehouse) dialects.

**Data export (CSV / Excel):** both the entity registry and a report view download the whole result set, not just the rendered page:
- `GET /api/v1/bizapps/{kind}/entities/{entity}/export?format=csv|xlsx&locale=vi|en` — every active record in entity field order (vi/en labels with a field-name fallback) plus `ID`/status/created/updated columns; capped at 10 000 records.
- `POST /api/v1/bizapps/{kind}/reports/{viewName}/export?format=csv|xlsx` — every column and row the view returns; workspace admins only.
- CSV is UTF-8 with a BOM (Excel renders Vietnamese correctly), every cell is quoted, and cells starting with `= + - @` gain a `'` prefix to block formula injection; XLSX is written with Apache POI `SXSSF` and keeps numeric cells numeric.

### 2. Knowledge Projection (Records → AI Assistant)

Allows the AI assistant to answer questions about business records **with verified citations** without modifying retrieval internals or using Text-to-SQL:
- Opted-in entities declare `knowledge: { "project": true, "titleField": "name", "fields": ["name", "stage"] }`.
- Each record is projected into a synthetic Markdown document (`bizapp_<kind>_<entity>_<recordId>.md`) indexed through Qdrant vector search and Kuzu knowledge graphs.
- **Fail-closed rule:** Projection is rejected unless `rbac.read` includes all workspace roles (`ORG_OWNER`, `WORKSPACE_ADMIN`, `MEMBER`). Fields marked `pii: true` are never projected.
- **Digest Document:** `bizapp_<kind>__digest.md` automatically compiles declared report views into aggregated Markdown tables for cited quantitative queries.

### 3. Visual Composer

- Low-code editor available at `(admin)/admin/apps/compose`:
  - **Visual Canvas:** Built with `@xyflow/react` and automated `@dagrejs/dagre` graph layouts, rendering `NodeEntity` and `NodeBlock`.
  - **List View:** Full keyboard navigation and accessibility-first representation (verified by `@axe-core/playwright`).
  - **JSON Tab:** Direct inspection and editing of canonical `bizapp.v1` JSON.
  - **Validation Panel:** Debounced real-time validation via `POST /api/v1/bizapps/validate`.

### 4. AI Manifest Drafting

- Endpoint: `POST /api/v1/bizapps/drafts/generate` (`ORG_OWNER` only, rate-limited, kill-switch `app.bizapps.ai-drafting.enabled`).
- Stored as untrusted drafts in `bizapp_drafts` (Flyway V14); installation re-validates the manifest from scratch.
- Governed by prompt isolation rules and evaluated against 20 benchmark prompts in `contracts/bizapp/eval/drafting-prompts.jsonl`.

### 5. Template Exchange (Export / Import / Remix)

Templates travel inside the standard `openvie.template.v1` envelope (`contracts/bizapp/openvie.template.v1.schema.json`):
```json
{
  "envelope": "openvie.template.v1",
  "meta": {
    "author": "LinkedNodeDigital",
    "license": "Apache-2.0",
    "source": "bundled",
    "sha256": "...",
    "generator": "human",
    "changes": []
  },
  "manifest": { ... }
}
```
- `GET /api/v1/marketplace/bizapps/{id}/export`: Download envelope JSON.
- `POST /api/v1/marketplace/bizapps/import`: Import via file or remote catalog URL, validating SHA-256 and SPDX license allow-lists (draft-only, never auto-installs).
- `POST /api/v1/marketplace/bizapps/{id}/remix`: Fork into a new draft, retaining upstream attribution and appending a change entry to `changes[]`.
- `GET /api/v1/marketplace/catalog`: Fetch catalog index entries from allow-listed hosts.

### 6. SSRF-Safe Outbound HTTP & e-Signature Recipe

The `external_call` block is a declarative connector layer (HTTP/HTTPS + MQTT):
- Every run is a durable job (`bizapp_connector_jobs`) executed after the record write commits; exponential retries (default 3, `PT10S` backoff, capped at 10 minutes), and each attempt is audited (`BIZAPP_EXTERNAL_CALL`) and metered (`bizapp_connector_runs_total` / `bizapp_connector_duration`).
- HTTP/HTTPS: HTTPS only (unless development override `app.bizapps.outbound.allow-http=true` is set); the host must exist in the installation `allowedHosts` config, and `egress.allowedHosts` (when declared) only narrows it further. DNS/SSRF checks, redirects disabled, response body capped at 256 KiB, sanitized logs.
- MQTT: `brokerUrl` uses `ssl://` (or `tcp://` when `app.bizapps.outbound.allow-http` is enabled), one bounded subscribe per job (`maxMessages`, `readMs` timeout) that always disconnects.
- Auth: vault header formats by default (`api_key`, `bearer`, `basic`), `auth.type=hmac` signs `X-OpenVie-Timestamp` + `X-OpenVie-Signature`, and `auth.type=oauth2` uses client-credentials with a token cache refreshed at 80% of the TTL. Credentials may be workspace-scoped.
- Idempotency header: `X-Idempotency-Key: <jobId>`; `responseMap` parameter extraction is stored in `runtime.external.<blockId>`.
- Sinks: `field` updates the current record; `record` appends/upserts the target entity by `dedupeKey` (unique index), so repeated polls never duplicate telemetry rows.
- `schedule` (`every` 30s..7d, `select`, `limit`) is scanned by the scheduler; records whose latest job is PENDING/RUNNING, or SUCCEEDED within `every`, are skipped.

### 7. Legal Boundaries

> **Legal Disclaimer:**
> OpenVie is an open-source software platform. OpenVie does **not** create, hold private signing keys for, or verify digital signatures; it delegates outbound requests through `external_call` to an external signing service contracted and configured by the operator.
> The legal validity and enforceability of signatures depend entirely on that external provider and applicable electronic transaction laws in the relevant jurisdiction. Operators must obtain their own legal advice.

### 8. Conversational BizApps & Smart Assistant Integration

In `v1.0.0`, the virtual assistant seamlessly understands business applications, recommends templates, offers safe interactive action cards, and drafts new applications from conversation history:

1. **Three-Tier Architecture:**
   - **Tier A (Deterministic):** Listing installed applications, manifest inspection, safe install/uninstall execution without model invocation or KB writes.
   - **Tier B (Structured LLM):** Intent classification (`LIST_APPS`, `APP_DETAIL`, `APP_DATA`, `INSTALL`, `UNINSTALL`, `SUGGEST`, `CREATE_APP`) via dedicated `CompleteJson` RPC with strict JSON schemas.
   - **Tier C (Document RAG):** Unmodified document retrieval, completely isolated from system document leakage.

2. **Interactive Action Cards:**
   - Delivered directly inside `chat_messages.action` (`jsonb`).
   - Lifecycle states: `PROPOSED -> EXECUTING -> EXECUTED | FAILED | DENIED`.
   - Server-authoritative endpoint: `POST /api/v1/chat/actions/execute`. Requires `ORG_OWNER` authority; execution parameters are re-read from the database by `messageId` and `actionId` rather than trusting client-provided payloads.

3. **Application Drafting from Conversation History:**
   - `POST /api/v1/bizapps/drafts/from-chat` (`ORG_OWNER`): extracts recent turns via `ChatRuntimeApi.listRecentTurns`, generates a `bizapp.v1` manifest draft via `CompleteJson`, validates against `BizAppManifestValidator`, and persists into `bizapp_drafts`.
   - Never installs automatically. The user reviews in Visual Composer or confirms installation via server-authenticated action cards.

### 9. Document Provenance & Safe Retrieval Access Rules

1. **Provenance Column `document_kind` (`USER`, `BIZAPP_RECORD`, `BIZAPP_DIGEST`):**
   - Human uploads are tagged `USER`.
   - Projected records are tagged `BIZAPP_RECORD` with `source_ref = "bizapp:<kind>"`.
   - Aggregated report views are tagged `BIZAPP_DIGEST`.
2. **Inviolable Access Guards:**
   - **Hidden by default:** Document lists omit system documents (`include_system=false`). Only `ORG_OWNER` can inspect them (`include_system=true`) for administrative review.
   - **User deletion blocked:** Attempting to delete system documents returns `403 Forbidden` (`AccessDeniedException`).
   - **Citation isolation:** Regular document queries (`intent = NONE`) exclude system documents across all channels (dense, sparse, and graph). Only explicit application data queries (`intent = APP_DATA`) scope to application records.
   - **Flat unique names:** Flat identifiers `bizapp__<kind>__<entity>__<id>.md` and `bizapp__<kind>__digest.md` prevent cross-app collisions.

### 10. Digest Debouncing & Revision Semantics

- **Debounced Digest:** `refreshDigest` is debounced to run at most once per 30-second window (`app.bizapps.projection-debounce-ms=30000`) per `(tenant, kind)`.
- **Revision Churn Immunity:** App install/uninstall does not touch the knowledge base, producing zero revision bumps and preventing `409 KNOWLEDGE_BASE_CHANGED` during active streams.
- **Observability Metrics:** `chat_turn_revision_conflicts_total`, `chat_action_execute_total{action,outcome}`, `chat_app_intent_total{intent}`.

### 11. Explicit Non-Goals

- No in-process digital signature creation or key custody.
- No vendor-specific inbound callback endpoints.
- No dynamic DDL execution generated from user manifests.
- No Text-to-SQL or LLM-generated database queries.
- No auto-install without explicit user confirmation.
- No detached cryptographic signature verification for remote templates.
