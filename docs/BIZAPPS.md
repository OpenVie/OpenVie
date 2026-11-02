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

Khối `external_call` được tăng cường bảo vệ an ninh mạng:
- Chỉ cho phép giao thức HTTPS (trừ khi bật dev profile `app.bizapps.outbound.allow-http`).
- Bắt buộc host phải nằm trong danh sách cho phép `allowedHosts` do ORG_OWNER cấu hình.
- Phân giải DNS và kiểm tra SSRF: chặn toàn bộ loopback, IP riêng RFC 1918, link-local, dải IP metadata đám mây (`169.254.169.254`).
- Tắt cơ chế tự động chuyển hướng (no redirects); giới hạn kích thước phản hồi tối đa 256 KiB; không lưu log query string hoặc giá trị header nhạy cảm.
- Tiêu đề chống gửi trùng lặp: `X-Idempotency-Key: <recordId>:<blockId>:<version>`.
- Trích xuất phản hồi `responseMap` qua JSON-pointer vào `runtime.external.<blockId>`.

### 7. Ranh giới pháp lý (Legal Boundaries)

> **Tuyên bố ranh giới pháp lý:**
> OpenVie là nền tảng phần mềm mã nguồn mở. OpenVie **không** tạo, không nắm giữ khóa ký riêng tư (private key), và không thực hiện quy trình ký số hay kiểm tra tính hợp lệ pháp lý của chứng thư điện tử. Mọi tác vụ ký số được thực hiện bằng cách ủy quyền thông qua khối `external_call` tới nhà cung cấp dịch vụ ký số bên ngoài do tổ chức tự hợp đồng và cấu hình.
> Giá trị pháp lý của văn bản ký kết phụ thuộc hoàn toàn vào dịch vụ của nhà cung cấp đó và các quy định pháp luật hiện hành tại quốc gia sở tại. Người vận hành hệ thống có trách nhiệm tự tham vấn ý kiến pháp lý chuyên môn cho các hoạt động ký kết điện tử của mình.

### 8. Các mục tiêu cố ý nằm ngoài phạm vi (Non-Goals)

- Không tích hợp thư viện ký số nhúng hay giữ private key trong quy trình.
- Không tạo endpoint webhook riêng biệt cho từng nhà cung cấp ký số (cập nhật trạng thái được thực hiện qua `PATCH /api/v1/bizapp-data/{kind}/{entity}/{id}` bằng API key máy `ovk_...`).
- Không tạo bảng cơ sở dữ liệu động từ manifest của người dùng (chỉ các template platform cốt lõi mới có bảng typed table).
- Không sử dụng Text-to-SQL hay truy vấn do LLM tự sinh.
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

The `external_call` block features strict SSRF defense:
- HTTPS only (unless development override `app.bizapps.outbound.allow-http=true` is set).
- Host must exist in installation `allowedHosts` config.
- DNS resolution checks reject loopback, RFC 1918 private IPs, link-local, and cloud metadata addresses (`169.254.169.254`).
- Redirects disabled; response body capped at 256 KiB; URLs sanitized in logs.
- Idempotency header: `X-Idempotency-Key: <recordId>:<blockId>:<version>`.
- Parameter extraction via `responseMap` stored in `runtime.external.<blockId>`.

### 7. Legal Boundaries

> **Legal Disclaimer:**
> OpenVie is an open-source software platform. OpenVie does **not** create, hold private signing keys for, or verify digital signatures; it delegates outbound requests through `external_call` to an external signing service contracted and configured by the operator.
> The legal validity and enforceability of signatures depend entirely on that external provider and applicable electronic transaction laws in the relevant jurisdiction. Operators must obtain their own legal advice.

### 8. Explicit Non-Goals

- No in-process digital signature creation or key custody.
- No vendor-specific inbound callback endpoints (status write-back occurs via standard `PATCH /api/v1/bizapp-data/{kind}/{entity}/{id}` using `ovk_...` API keys).
- No dynamic DDL execution generated from user manifests (typed storage is platform-bundled only).
- No Text-to-SQL or LLM-generated database queries.
- No detached cryptographic signature verification for remote templates (pinned via SHA-256 hashes in v1.0.0).
