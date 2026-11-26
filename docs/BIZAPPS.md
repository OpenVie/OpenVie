# BizApps — nền tảng ứng dụng kinh doanh cài được (Tier 3)

[Danh mục tài liệu](README.md) · [Kiến trúc](ARCHITECTURE.md) · [Tích hợp](INTEGRATION.md) · [Workflow](WORKFLOW.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#bizapps--nền-tảng-ứng-dụng-kinh-doanh-cài-được) · [English](#bizapps--the-installable-business-app-platform)
>
> Tài liệu tham chiếu đầy đủ cho manifest `bizapp.v1`: các khối thực thi, composer trực quan,
> báo cáo khai báo, đồng bộ tri thức cho trợ lý AI, API runtime và các giới hạn/lỗi thực tế của bản
> cài hiện tại. Nguồn chuẩn: `contracts/bizapp/bizapp.v1.schema.json`, `api/src/main/java/com/openvie/api/bizapp/`
> (validator/executor/composer) và `frontend/src/components/bizapp/composer/`.

## BizApps — nền tảng ứng dụng kinh doanh cài được

### 0. Mô hình tổng quát (TL;DR)

BizApp là **ứng dụng kinh doanh khai báo** chạy trực tiếp trên các service headless của nền tảng:
không cần backend riêng. Toàn bộ hành vi của ứng dụng nằm trong **một tệp JSON** theo hợp đồng
`bizapp.v1`, gọi là **manifest**:

```mermaid
flowchart LR
    M[Manifest bizapp.v1<br/>entities · blocks · reports · rbac · ui] -->|install| I[Chốt manifest<br/>+ deploy quy trình BPMN]
    I --> R[Runtime<br/>registry / form / timeline / report / assistant]
    R -->|hooks after commit| H[Phê duyệt Flowable · Notify · Webhook · Connector]
    R -->|projection| K[KB `bizapp-&lt;kind&gt;`<br/>tài liệu bản ghi + digest]
    K --> C[Chat có trích dẫn]
```

Ba giao diện người dùng:

| Giao diện | Đường dẫn | Ai dùng |
| --- | --- | --- |
| Kho ứng dụng và console quản trị (cài/gỡ, template, credential, bản thảo AI) | `/admin/apps`, `/admin/apps/compose` | Chủ tổ chức |
| Ứng dụng runtime (registry, form, timeline, report, assistant) | `/apps`, `/apps/{kind}` | Theo `rbac` trong manifest |
| Trợ lý hội thoại (liệt kê, cài đặt, gợi ý, tạo app, bản ghi) | Chat workspace | Theo vai trò |

> **Mô hình triển khai:** bản OSS self-host có **một organization** cho mỗi lần cài; `org` trong schema/API là tenant mặc định, không phải giao diện nhiều tổ chức. Catalog template gồm **built-in** (JSON trong codebase) và **company** (DB của tổ chức đó).

### 1. Bắt đầu nhanh (10 phút)

1. `/admin/apps/compose`: đặt tên (Vi/En), chỉnh entity mặc định `records` (thêm field, kiểu,
   required, PII) và đổi tên entity.

   > Composer chỉ hiện một số thuộc tính thân thiện: tên, nhãn, kiểu, required, PII. Các cấu hình sâu hơn
   > (`maxLen`, `format`, `options`, `optionLabels`, `knowledge`, và nhiều nhất là tham số riêng của
   > từng khối) đặt qua **tab JSON** hoặc **Additional settings** trong inspector.

2. Thêm khối thực thi, ví dụ **Approval** (quy trình 1/2 cấp và vai trò duyệt) hoặc **Report**.
   Nối entity → khối để tạo hook `onCreate`/`onUpdate`/`onDecide` (biểu tượng ghi chú trên edge).

3. Xem panel kiểm tra hợp lệ (debounce 400 ms, gọi `POST /api/v1/bizapps/validate`): chấm xanh khi
   hợp lệ, đỏ khi lỗi kèm thông báo dễ hiểu.

4. **Lưu bản thảo** hoặc **Cài đặt** (tự nâng cấp nếu app cùng kind đã cài). Khi cài, manifest được chốt,
   BPMN được triển khai rồi chuyển tới `/apps/{kind}`.

5. Thêm bản ghi bằng form, gửi duyệt, xem report và trao đổi qua assistant.

### 2. Tham chiếu manifest `bizapp.v1`

Schema máy đọc được: `contracts/bizapp/bizapp.v1.schema.json` (`additionalProperties: false`). Mọi key
ngoài hợp đồng đều bị từ chối kèm gợi ý sửa (ví dụ `maxLength` → "key đúng là `maxLen`").

#### 2.1 Cấu trúc cấp cao nhất

| Key | Kiểu/giá trị | Mặc định | Ghi chú |
| --- | --- | --- | --- |
| `schema` | `"bizapp.v1"` (const) | — | bắt buộc |
| `kind` | `[a-z][a-z0-9-]{1,31}` | — | duy nhất trong mỗi lần cài; trùng kind khi cài → `409` |
| `name` | `{vi, en}` nhãn hiển thị ≤200 ký tự | — | bắt buộc, dùng làm tiêu đề |
| `version` | int ≥1 | 1 | khi nâng cấp: `max(bản đang cài + 1, yêu cầu)` |
| `icon` | chuỗi ≤64 | — | tên icon (fallback emoji ở `/apps`) |
| `storage` | `jsonb` | `jsonb` | `typed` bị từ chối trong bản build này (mọi app dùng `bizapp_records`) |
| `rbac` | map `read/write/delete/approve/submit` → danh sách vai trò | xem §2.3 | |
| `entities` | 1..20 đối tượng | — | bắt buộc |
| `blocks` | ≤40 đối tượng | — | |
| `conversational` | `{triggers[≤50], description≤2000}` | — | từ khóa cho router ngữ nghĩa |
| `ui` | `{views[≤20], layout}` | — | thứ tự view và tọa độ canvas |

#### 2.2 `entities` và `fields`

**Entity:** `name` (`[a-z][a-z0-9_]{0,63}`, duy nhất), `label {vi,en}` (bắt buộc — validator từ chối entity thiếu label),
`fields` (1..40), `knowledge`, `hooks`.

**Field:**

| Key | Giá trị | Mặc định | Ghi chú |
| --- | --- | --- | --- |
| `name` | `[a-z][a-z0-9_]{0,63}` (tự chuẩn hóa về chữ thường) | — | duy nhất trong entity |
| `type` | `string` `number` `boolean` `enum` `date` `ref` | — | |
| `entity` | tên entity | — | bắt buộc khi `type=ref`; không được trỏ tới entity chưa khai báo |
| `required` | bool | false | chặn tạo mới khi thiếu; khi cập nhật chỉ chặn nếu field có mặt nhưng rỗng |
| `maxLen` | int 1..2000 | 1024 cho `string` | KHÔNG phải `maxLength` |
| `format` | `email` `uri` `phone` | — | hiện chỉ `email` được kiểm tra ở server |
| `options` | danh sách chuỗi | — | bắt buộc với `enum`; khi ghi, so khớp không phân biệt hoa/thường |
| `optionLabels` | map option → `{label, aliases}` | — | nhãn/alias dùng cho trích slot trong chat |
| `pii` | bool | false | field không bao giờ được đưa lên KB |
| `label` | `{vi,en}` | fallback tên field | dùng ở form/registry/export/assistant |

`date` nhận ISO (`2026-01-31`, `2026-01-31T10:30`), `d/m/y` và ngôn ngữ tự nhiên ("hôm nay", "mai",
"thứ 4", "next Friday"); phần giờ được chuẩn hóa về `HH:mm`.

#### 2.3 `rbac` được thực thi thế nào

| Khả năng | Mặc định khi không khai báo | Ghi chú |
| --- | --- | --- |
| `read` | mọi vai trò workspace | `ORG_OWNER` luôn đọc được |
| `write` | chỉ admin workspace | thêm `MEMBER` để cho member ghi |
| `delete` | chỉ admin workspace | |
| `approve` | chỉ admin workspace | `candidateRole` của khối duyệt phải nằm trong danh sách này nếu có khai báo |
| `submit` | mặc định theo `write` | hoặc danh sách riêng |

Runtime **không tin vai trò do client gửi**; vai trò manifest được xác định lại từ phiên và org ở mỗi request.
Runtime cũng tôn trọng `entities[].rbac.write/submit` (cấp theo entity, thường do AI draft sinh ra),
nhưng **`rbac` ở top-level là chuẩn** — schema đã chốt không khai báo key theo entity.

#### 2.4 `knowledge` (đồng bộ tri thức)

```json
"knowledge": { "project": true, "titleField": "name", "fields": ["name", "amount", "stage"] }
```

Chỉ bật khi `rbac.read` chứa **cả ba** vai trò (nguyên tắc fail-closed); `titleField`/`fields` phải tồn tại
trong entity; field `pii: true` không được đưa lên KB. Chi tiết ở §7.

#### 2.5 `hooks` (lifecycle → khối)

| Hook | Khi nào chạy | Ghi chú |
| --- | --- | --- |
| `onCreate` | sau khi tạo bản ghi thành công | approval → **tự gửi duyệt** |
| `onUpdate` | sau khi patch bản ghi | như trên; bản ghi đang `PENDING` không khởi động lại quy trình |
| `onDecide` | sau khi có quyết định duyệt | luôn best-effort, không target `approval_workitem` |

Target là **block id** của khối hook hợp lệ (`approval_workitem`, `notify`, `webhook_out`, `external_call`)
hoặc `webhook_out:<event>` (event phải do một khối `webhook_out` khai báo). Thứ tự chạy cho mỗi event:
**approval → external_call → notify/webhook/custom** — phần có thể rollback chạy trước, để thông báo
không bao giờ báo trước về một bản ghi mà transaction sẽ rollback. Cụ thể: `external_call` chỉ **enqueue**
job trong transaction — lỗi provider do worker retry, không rollback bản ghi; lỗi `notify`/`webhook_out`
cũng chỉ được audit, không làm hỏng write.

Kết quả hook được audit (`BIZAPP_HOOK_EXECUTED`/`BIZAPP_HOOK_FAILED`) và ghi metric.

#### 2.6 `ui`

`views` là mảng `"<prefix>:<target>"` (≤20, mỗi mục ≤100 ký tự):

| Prefix | Target | Render |
| --- | --- | --- |
| `registry:` | entity | bảng bản ghi có phân trang, sửa, lưu trữ, gửi duyệt, export |
| `form:` | entity | registry + hộp thoại tạo bản ghi mở sẵn |
| `timeline:` | entity | feed thẻ bản ghi theo thời gian |
| `report:` | view | bảng dữ liệu của view (admin workspace) |
| `assistant:` | block id `assistant` | khung chat trong app |

Validator từ chối view trỏ tới entity/view/block không tồn tại (`unknown ui view`). Composer tự thêm
`report:<name>` và xóa khi view bị xóa. `layout` lưu tọa độ `{x,y}` (±10000) cho canvas.

#### 2.7 `conversational`

`triggers` (≤50, mỗi mục ≤200) và `description` (≤2000) được router ngữ nghĩa dùng để **chọn đúng app**
khi tin nhắn không nhắc tên kind (ví dụ "tạo phiếu xin mua"). Tránh từ khóa chung chung như "quản lý".

#### 2.8 Ví dụ tối thiểu

```json
{
  "schema": "bizapp.v1",
  "kind": "asset-request",
  "name": { "vi": "Yêu cầu tài sản", "en": "Asset request" },
  "version": 1,
  "storage": "jsonb",
  "rbac": {
    "read": ["ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER"],
    "write": ["WORKSPACE_ADMIN", "MEMBER"],
    "approve": ["WORKSPACE_ADMIN"]
  },
  "entities": [{
    "name": "requests",
    "label": { "vi": "Yêu cầu", "en": "Requests" },
    "fields": [
      { "name": "title", "type": "string", "required": true, "maxLen": 200 },
      { "name": "amount", "type": "number" },
      { "name": "stage", "type": "enum", "options": ["NEW", "APPROVED", "REJECTED"] }
    ],
    "hooks": { "onCreate": ["approval1"] }
  }],
  "blocks": [
    { "id": "approval1", "type": "approval_workitem",
      "params": { "bpmnTemplate": "processes/bizapp-single-approval.bpmn20.xml",
                  "candidateRole": "WORKSPACE_ADMIN",
                  "sla": { "reminder": "PT24H", "escalation": "PT48H" } } },
    { "id": "notify1", "type": "notify",
      "params": { "to": ["creator"], "title": "Yêu cầu mới", "subject": "Yêu cầu {{record.title}}" } }
  ],
  "utils": {},
  "conversational": { "triggers": ["yêu cầu tài sản", "asset request"] },
  "ui": { "views": ["registry:requests", "form:requests", "timeline:requests"] }
}
```

### 3. Danh mục khối (blocks)

Quy tắc chung: `id` duy nhất theo `[A-Za-z][A-Za-z0-9_]{0,63}`; hook chỉ chạy được với
`approval_workitem` / `notify` / `webhook_out` / `external_call`. `report`/`knowledge_base`/`assistant`
là khối chức năng nhưng **không được target bởi lifecycle hook**. Các tab runtime `registry:`/`form:`/
`timeline:` nằm trong `ui.views`, không phải block type.

#### 3.1 `approval_workitem` (tối đa 1 khối/app)

- `params.bpmnTemplate` phải theo dạng `processes/<name>.bpmn20.xml` (chống path traversal). Template đi kèm:
  `bizapp-single-approval.bpmn20.xml`,
  `bizapp-two-level-approval.bpmn20.xml`, `eoffice-document-approval.bpmn20.xml`. Nếu template không tồn tại
  → **fallback** về single-approval chung (KHÔNG abort cài đặt).
- `candidateRole` (mặc định `WORKSPACE_ADMIN`) phải nằm trong `rbac.approve` nếu `rbac.approve` được khai báo.
- `sla.reminder` (mặc định `PT24H`) / `sla.escalation` (mặc định `PT48H`): chuỗi ISO-8601.
- Khi cài, Flowable triển khai process key `bizapp-<kind>` cho từng workspace; biến task gồm `recordId`, `entity`,
  `appKind`, `workspaceId`, `initiatorId`, `candidateRole`, `reminderDuration`, `escalationDuration`.
- Claim/complete dùng **task inbox chung của engine** (không phải API riêng); delegate ghi quyết định về
  bản ghi: `runtime.approvalStatus = APPROVED | REJECTED`, `runtime.processId`.

#### 3.2 `notify`

- `params.to` (bắt buộc, dạng list): `creator` (tác giả **bản ghi**, không phải người chạy hook) |
  `record.<emailField>` (field string phải được khai báo trên **mọi entity có hook trỏ tới block này**) | `config.<key>` | một địa chỉ email cụ thể.
- `subject`/`title` dùng biến `{{record.<field>}}`/`{{<field>}}`.
- Gửi HTML qua kênh email của tổ chức; **lỗi gửi được audit nhưng không hủy giao dịch**; nếu không xác định
  được người nhận, block ghi cảnh báo rồi bỏ qua.

#### 3.3 `webhook_out`

- `params.events` (bắt buộc): các sự kiện đã biết `record.created|updated|archived|restored|submitted|decided`
  (hoặc pattern tùy ý `[a-z0-9][a-z0-9._-]{1,62}`).
- Record service chỉ đẩy event mà **một khối nào đó khai báo**. `hook: ["webhook_out:custom.event"]`
  đẩy sự kiện tùy ý `bizapp.<kind>.custom.event` ngay khi hook chạy (best-effort).
- Sự kiện được ký HMAC và fan-out qua module tích hợp (webhook dispatcher), không chạy trong transaction.

#### 3.4 `external_call` (connector khai báo)

| Param | Miền giá trị | Mặc định | Ghi chú |
| --- | --- | --- | --- |
| `protocol` | `http` \| `mqtt` | `http` | |
| `url` | URL tuyệt đối | — | bắt buộc với `http`; phải là HTTPS (trừ khi bật `allow-http`) |
| `method` | `GET` `HEAD` `POST` `PUT` `PATCH` `DELETE` | `POST` | `GET/HEAD` **không gửi body** (API chỉ nhận GET sẽ không nhận POST ngầm) |
| `bodyTemplate` | chuỗi JSON | — | chỉ hợp lệ với POST/PUT/PATCH; hỗ trợ `{{record.<field>}}` |
| `responseMap` | map → JSON pointer `/path` | — | ≤8 mục; kết quả ghi vào `runtime.external.<blockId>` |
| `credentialRef` | tên/id của vault entry | — | bắt buộc với auth `header`/`oauth2` (HTTP); dùng cho credential MQTT |
| `auth` | `{type: header|hmac|oauth2, ...}` | `header` | `hmac` cần `secretRef` và tùy chọn `header`/`timestampHeader`; `oauth2` cần `tokenUrl` HTTPS + `credentialRef` (`client_id:client_secret`), token được cache khoảng 80% TTL |
| `sink` | `{type: field\|record, entity, mapping, dedupeKey}` | `field` | `field`: ghi ngược vào chính bản ghi gốc; `record`: yêu cầu khai báo `sink.entity`, và `dedupeKey` (1..5 phần tử) phải nằm trong các key của `mapping` (upsert idempotent) |
| `mapping` | `"<entity field>": "/path"` | — | mọi field đích phải được khai báo trên entity |
| `schedule` | `{every, entity, select, limit}` | — | `every` từ PT30S đến P7D; `select` tối đa 5 field đã khai báo; `limit` 1..500 (mặc định 50) |
| `timeout` | `{connectMs, readMs}` | 10000/15000 | 100..60000 ms |
| `retry` | `{maxAttempts, backoff}` | 3 / PT10S | 1..10, PT1S..PT10M; exponential ×2, tối đa 10 phút |
| `egress.allowedHosts` | danh sách host | — | phải chứa host đích; chỉ được **thu hẹp** danh sách của bản cài |

Ranh giới bảo mật: bản cài phải có `config.allowedHosts` (PATCH app config); thiếu nó thì connector từ chối
gọi. Secret inline (key dạng `password`/`secret`/`token`/`api_key`) bị validator từ chối; PEM private key bị từ
chối ở vault; response tối đa 256 KiB; không redirect; chặn DNS/SSRF (loopback/private/link-local/metadata).
Mỗi lần chạy là một job bền vững (`PENDING/RUNNING/SUCCEEDED/DEAD`), được audit và đo metric; lỗi
`4xx/5xx`/timeout được retry exponential (tối đa 10 phút) tới `maxAttempts` rồi chuyển `DEAD`.

MQTT: subscribe một lần cho mỗi job (`qos`, `maxMessages`, `readMs`), luôn disconnect; credential dạng
`mqtt` → `username:password`.

#### 3.5 `knowledge_base` và `assistant`

- `knowledge_base`: `params.graphExtraction` tùy chọn; khi chạy sẽ tạo/lấy KB `bizapp-<kind>`. **Không cần
  cấu hình thủ công.**
- `assistant`: `params.instructions` hiện như lời chào/hướng dẫn trên tab assistant. Khi chạy là
  no-op (tab dùng chat session workspace bình thường). `params.knowledgeBaseBlock` chỉ để **vẽ edge**
  tới knowledge base trên canvas — không ảnh hưởng runtime.

### 4. Báo cáo khai báo (thay SQL tự do)

Báo cáo là các view khai báo bên trong block `report` (`params.views`). **Không có chuỗi SQL**:
nếu view chứa `sql`, validator từ chối. Định nghĩa view:

| Key | Ý nghĩa |
| --- | --- |
| `name`, `entity` | bắt buộc; entity phải được khai báo |
| `title` | metadata hiển thị (tiêu đề tab report, do composer tạo) |
| `fields[]` | danh sách field cho **list view** (chỉ dùng khi không có `groupBy`/`metrics`) |
| `groupBy[]` | cột group-by (chỉ field đã khai báo) |
| `metrics[]` | `{fn: count\|sum\|avg\|min\|max, field?, as}` — `sum/avg` chỉ nhận field `number`; `count` có thể bỏ `field` |
| `filters[]` | `{field, op, value? / values? / param?}` — `op`: `eq ne in gt gte lt lte contains isnull` (`contains` chỉ cho field `string`); `param` gắn giá trị runtime |
| `orderBy[]` | `{by, dir: asc\|desc}`, `by` phải là field đã khai báo hoặc alias của metric/groupBy |
| `limit` | 1..500 (mặc định 100); SQL luôn có `LIMIT` theo mức này |
| `engine` | `postgres` (JSONB qua `payload->>'field'`; `number` cast `::numeric`) hoặc `lakehouse` (DuckDB, cần cấu hình lakehouse) |

Compiler luôn áp `org_id`, `tenant_id` + (với JSONB: `app_kind`, `entity`) + `status='ACTIVE'` và **chỉ bind
named param** — bảng nguồn do storage layer quyết định, manifest không thể nhắm tới bảng khác. Param runtime
bị chặn `orgId`/`tenantId`/`appKind`/`entityName`.

Quyền: **chỉ admin workspace** chạy report (`POST /bizapps/{kind}/reports/{viewName}`), và tab report chỉ
hiện với admin. Runtime hiển thị **tối đa 6 cột**; export trả **toàn bộ cột/dòng**. View không có dòng/limit
thì không export được (file rỗng), nhưng report vẫn an toàn.

### 5. Các view runtime, bản ghi và phê duyệt

| View | Hành vi |
| --- | --- |
| `registry:` | phân trang 20 bản ghi (`updated_at desc`), refresh, export CSV/XLSX, tạo, sửa, lưu trữ mềm (status `ARCHIVED`), **khôi phục**, bật/tắt **Show archived**, gửi duyệt (chỉ khi app có khối approval) |
| `form:` | giống registry nhưng tự mở hộp thoại tạo bản ghi; form sinh từ manifest, field không khai báo thì không nhập được (server cũng trả 400) |
| `timeline:` | feed thẻ các bản ghi mới nhất, 3 field đầu và badge trạng thái |
| `report:` | bảng kết quả của view (chỉ admin), refresh, export |
| `assistant:` | khung chat trong app (dùng chat session workspace bình thường, §7) |

Trạng thái bản ghi:

| Trường | Giá trị |
| --- | --- |
| `status` | `ACTIVE` \| `ARCHIVED` (xóa mềm; không có endpoint xóa cứng từ client; purge theo retention là job phía server) |
| `archivedAt` / `archivedBy` | thời điểm/người lưu trữ; xóa khi restore |
| `runtime.approvalStatus` | `PENDING` \| `APPROVED` \| `REJECTED`; `runtime.processId` khi đang chạy |

Bản ghi đang `PENDING` **không được lưu trữ** (409, phải withdraw trước). Bản ghi `ARCHIVED` **không sửa được**
(400, phải restore). Restore trả status `ACTIVE`, giữ nguyên payload/runtime (audit quyết định vẫn còn), phát
`record.restored` và cập nhật lại tài liệu knowledge (thao tác archive đã xóa tài liệu).

API bản ghi bị chặn bởi `rbac` trong manifest, và vai trò manifest được xác định lại từ request (client không
gửi được); entity không khai báo → 404. `payload` được chấp nhận dạng patch; field lạ bị từ chối 400. Update khi
`PENDING` **giữ nguyên runtime** (không phá process); update sau khi đã có quyết định thì xóa quyết định cũ.

### 6. Composer trực quan

**Route:** `/admin/apps/compose` (app mới; `?template=<platform:<kind>|<uuid>` để bắt đầu từ template)
và `/admin/apps/compose/{draftId}`.

**Dashboard:** 3 tab trên header:

| Tab | Nút/mục |
| --- | --- |
| Canvas | **Auto Layout** (Dagre trái→phải); Palette bên trái; canvas ở giữa; Inspector bên phải |
| List | thẻ entity/block: chọn (nhảy tới inspector), xóa, thêm entity |
| JSON | sửa manifest trực tiếp; Format/Copy; lỗi cú pháp hiện ngay; JSON ↔ canvas tự đồng bộ |

**Canvas:**

| Edge | Ý nghĩa |
| --- | --- |
| entity → entity (field ref) | sinh tự động, chỉ đọc (kéo di chuyển không xóa) |
| entity → block (`hook`) | kéo từ entity sang block; mỗi lần kéo thêm event kế tiếp (`onCreate` → `onUpdate` → `onDecide`); bấm edge để đổi hook hoặc xóa |
| block `report` → entity | sinh từ views; xóa edge hoặc xóa view đều xóa view thật (không chỉ xóa edge) |
| block `external_call` → entity (`schedule`) | nét đứt, chỉ đọc; chỉnh schedule trong Inspector |
| assistant → knowledge_base | sinh ra khi `params.knowledgeBaseBlock` trỏ tới khối |

**Block registry:** `contracts/bizapp/blocks.v1.json` là nguồn duy nhất cho type/hookable/palette/defaults; backend và composer cùng đọc tệp này. Muốn thêm block type, bắt đầu từ registry, không sửa các danh sách rời.

**Palette:** New Entity + 7 khối với mặc định an toàn (report tạo 1 view đánh dấu entity đầu tiên; webhook
`record.created`/`record.updated`; notify `["creator"]`; approval single + `WORKSPACE_ADMIN`;
knowledge_base để trống, assistant chọn KB trong Inspector; thêm/sửa assistant tự thêm tab `assistant:<id>` vào `ui.views`).

**Inspector:**

- Entity: name, label EN, danh sách field (name/type/required/PII, thêm/xóa). Các cấu hình field sâu
  (label vi/en, `maxLen`, `format`, `options`, `optionLabels`, `knowledge`) sửa trong **tab JSON**.
- Approval: quy trình 1/2 cấp (preset BPMN) và vai trò duyệt; giải thích 2 cấp.
- External Call: URL, credentialRef; các tham số còn lại qua **Additional settings** (editor
  scalar/list/object cho key UI chưa xử lý).
- Notify: recipients (creator/record.*/config.*/email), Title, Subject.
- Report: **Report Builder** (title, entity, list hoặc summary, fields, metrics + alias, filters, sort,
  limit 1..500; chuẩn hóa tên view thành slug từ title; đổi entity sẽ reset groupBy/metrics).
- Webhook: checkbox các event chuẩn.
- Knowledge base: ghi chú "tự động".

**Panel kiểm tra hợp lệ:** debounce 400 ms, gọi `POST /bizapps/validate`; nút Install bị vô hiệu hóa khi không hợp lệ.
Phản hồi trả thêm `issues: [{path, message}]`; composer gán `entities.<name>`/`blocks.<id>` cho node tương ứng để
hiện viền đỏ và tooltip, còn panel vẫn liệt kê toàn bộ lỗi.
Có thể đổi ngôn ngữ palette/theme ngay trên header.

**Thao tác:** Save draft (`PUT /drafts/{id}` hoặc `PUT /drafts/new` khi tạo mới), Save as Template (mở hộp thoại
name/summary rồi `POST /marketplace/bizapps`), Install — nếu kind đã cài thì **nâng cấp tại chỗ** (version
tự tăng, giữ bản ghi) thay vì báo "already installed". Thẻ app đã cài trong `/admin/apps` cũng có
**Save as template** dùng manifest đã chốt hiện tại.

**Phím tắt composer:** `Ctrl/Cmd+S` lưu bản thảo, `Ctrl/Cmd+Z` hoàn tác, `Shift+Ctrl/Cmd+Z`/`Ctrl+Y` làm lại, `Delete`/`Backspace` xóa node/edge đang chọn (kèm dọn view/report/hook liên quan), `Esc` đóng Inspector hoặc hộp thoại edge.

### 7. Đồng bộ tri thức và trợ lý AI

- **Tài liệu bản ghi:** với entity bật `knowledge.project`, mỗi bản ghi được chuyển thành một tài liệu Markdown
  `bizapp__<kind>__<entity>__<recordId>.md` (chỉ các field trong `knowledge.fields`, bỏ field
  `pii`), `source_ref` `bizapp:<kind>:<entity>`, `document_kind=BIZAPP_RECORD`.
- **Digest:** `bizapp__<kind>__digest.md` tổng hợp mọi view đã khai báo (mỗi view một bảng; ghi `No active data
  recorded.` nếu không có dòng), rebuild với **debounce 30 giây** (`BIZAPPS_PROJECTION_DEBOUNCE_MS`) sau mỗi
  lifecycle event của bản ghi (tạo/sửa/gửi/quyết định), nhưng không chạy khi cài/gỡ app (giữ `search_revision`
  ổn định, tránh 409 cho chat đang stream). Record event được phát **vô điều kiện** cho mọi write qua API bản
  ghi; việc có block `webhook_out` hay không chỉ ảnh hưởng **giao webhook**, không ảnh hưởng projection/CDC.
- **KB đích:** mặc định là KB riêng `bizapp-<kind>` (tự tạo). Chat chỉ truy hồi KB gắn với chatbot của phiên —
  tức **KB mặc định của workspace**. Muốn app đưa dữ liệu vào KB chat, đặt `config.knowledgeBaseId` (PATCH
  `/bizapps/{kind}/config`, chỉ `ORG_OWNER`) rồi **cập nhật một bản ghi** để đồng bộ lại.
- **Lọc tài liệu hệ thống:** `document_kind` được đẩy vào Qdrant payload/index; chat thường mặc định loại
  `BIZAPP_RECORD`/`BIZAPP_DIGEST`, còn chat theo app có thể truy hồi record nếu được cấu hình.
- **Trợ lý trong app (tab `assistant:`):** tạo chat session workspace bình thường; `instructions`
  hiển thị như tiêu đề phụ; router suy ra app từ nội dung (không tự giới hạn theo app).
- **Cách chạy:** thật, không Text-to-SQL. Số liệu tổng hợp cần **chính xác tuyệt đối** phải lấy từ report view
  (tab report hoặc export) — chat trả lời theo top-k truy hồi nên không đảm bảo phủ mọi bản ghi.

**Giới hạn của bản build này** (nêu rõ để tránh hiểu nhầm):

1. Truy hồi là top-K chunk nên không phủ hết bản ghi; digest chỉ là bản sao văn bản của view đã khai báo.
2. Ghi qua **connector** (sink record/field) không phát sự kiện vòng đời → việc đồng bộ/digest có thể chậm sau khi
   connector ghi; PATCH bản ghi qua API luôn làm mới.
3. Chat của app chưa tự giới hạn theo app; câu hỏi không nhắc tên app có thể bị trộn tài liệu của app khác.

**Cách ly theo workspace:** nếu cần tách dữ liệu giữa các app, tạo workspace riêng, chỉ cài một app,
bật `knowledge.project` cho entity cần đồng bộ (và `rbac.read` chứa đủ ba vai trò), trỏ
`config.knowledgeBaseId` về KB mặc định của workspace đó,
rồi tạo API key gắn với workspace đó (`POST /api/v1/admin/api-keys`). Chat và API key chỉ thấy KB của
workspace; đây là cách ly theo tenant/workspace, không phải app-scope — mọi tài liệu trong KB đó vẫn có
thể được truy hồi.

### 8. Danh sách API

Mọi đường dẫn dưới đây đều có tiền tố `/api/v1`.

| Method & đường dẫn | Quyền | Mô tả |
| --- | --- | --- |
| `GET /bizapps`, `GET /bizapps/{kind}` | mọi thành viên | app đã cài và manifest đã chốt |
| `GET /bizapps/installed-summary`, `GET /bizapps/installed-anywhere` | mọi thành viên | bản tóm tắt cho chat/catalog; cờ cho biết app đã cài ở bất kỳ workspace nào |
| `GET /bizapp-data/{kind}/{entity}` | `rbac.read` | phân trang bản ghi (`page`, `size≤100`, `includeArchived`) |
| `POST /bizapp-data/{kind}/{entity}` | `write` | tạo bản ghi |
| `GET /bizapp-data/{kind}/{entity}/{recordId}` | `rbac.read` | xem một bản ghi |
| `PATCH /bizapp-data/{kind}/{entity}/{recordId}` | `write` | sửa bản ghi |
| `DELETE /bizapp-data/{kind}/{entity}/{recordId}` | `delete` | lưu trữ bản ghi |
| `POST …/{recordId}/restore` | `delete` | khôi phục bản ghi đã lưu trữ |
| `POST …/{recordId}/submit` | `submit` | bắt đầu quy trình phê duyệt |
| `POST /bizapps/{kind}/reports/{view}` | workspace admin | chạy view (body `{"params": {...}}`) |
| `POST /bizapps/{kind}/reports/{view}/export` | workspace admin | CSV/XLSX đầy đủ cột/dòng |
| `GET /bizapps/{kind}/entities/{entity}/export` | `rbac.read` | CSV/XLSX bản ghi (`ACTIVE`; `includeArchived=true` để gồm cả lưu trữ, tối đa 10 000) |
| `PATCH /bizapps/{kind}/config` | ORG_OWNER | thay `config` (ví dụ `allowedHosts`, `knowledgeBaseId`) |
| `POST /bizapps/validate` | ORG_OWNER | kiểm tra hợp lệ mà không lưu |
| `POST /bizapps/drafts/generate`, `POST /bizapps/drafts/from-chat` | ORG_OWNER | soạn bằng AI hoặc từ chat (≤20 lượt gần nhất) |
| `GET /bizapps/drafts`, `GET/PUT/DELETE /bizapps/drafts/{id}`, `PUT /bizapps/drafts/new` | ORG_OWNER | đọc/tạo/sửa/xóa bản thảo |
| `GET /marketplace/bizapps`, `GET /marketplace/bizapps/{templateRef}` | mọi thành viên | catalog (built-in + company) |
| `POST /apps/install`, `POST /apps/{kind}/upgrade`, `DELETE /apps/{kind}` | ORG_OWNER | cài / nâng cấp / gỡ mềm |
| `POST /marketplace/bizapps`, `DELETE /marketplace/bizapps/{templateId}` | ORG_OWNER | lưu/xóa template của công ty |
| `GET /marketplace/bizapps/{templateRef}/export`, `GET /marketplace/catalog` | mọi thành viên | xem/xuất template và catalog |
| `POST /marketplace/bizapps/import`, `POST /marketplace/bizapps/{templateRef}/remix` | ORG_OWNER | nhập/remix template |
| `GET/POST /bizapp-credentials`, `DELETE /bizapp-credentials/{credentialId}` | ORG_OWNER | kho credential |

`templateRef`: `platform:<kind>` cho template tích hợp sẵn (crm/booking/signature-request), hoặc
UUID cho template của công ty.

### 9. Cấu hình

| Key (env) | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `BIZAPPS_ENABLED` | true | công tắc tắt: `false` → mọi route `/bizapp*` trả 404 |
| `BIZAPP_ENC_KEY` | — | bắt buộc để lưu credential; `openssl rand -base64 32` |
| `BIZAPPS_OUTBOUND_ALLOW_HTTP` | false | cho phép `http://` (dev) |
| `BIZAPPS_OUTBOUND_ALLOW_PRIVATE_HOSTS` | false | dev: cho phép host loopback/private (mock server) |
| `BIZAPPS_AI_DRAFTING_ENABLED` | true | tắt → AI drafting trả 404 |
| `BIZAPPS_AI_DRAFT_MODEL` / `_FALLBACK_MODEL` | trống | model `CompleteJson` cho drafting; trống = mặc định của AI service |
| `BIZAPPS_PROJECTION_DEBOUNCE_MS` | 30000 | debounce của digest |
| `BIZAPPS_REMOTE_CATALOG_ENABLED` / `_HOSTS` | false / trống | allowlist host cho `GET /marketplace/catalog` và import theo URL |
| `app.bizapps.connectors.*` (`enabled`, `scan-delay-ms`=15000, `worker-delay-ms`=5000, `batch-size`=20) | | scheduler/worker của connector |
| `app.retention.workspace-days` / `bizapp-record-days` | 0 (tắt) | ngưỡng đủ điều kiện purge; workspace chỉ được báo cáo |
| `app.retention.bizapp-record-purge-enabled` | false | bật xóa cứng bản ghi BizApp đã archive quá hạn |
| `app.retention.max-records-per-run` | 200 | giới hạn purge mỗi lần chạy |
| `app.retention.scan-delay-ms` / `initial-delay-ms` | 86400000 / 300000 | chu kỳ quét retention |
| `CHAT_BIZAPP_ACTIONS_ENABLED` / `CHAT_BIZAPP_RECORDS_ENABLED` | true | công tắc tắt cho chat actions/records |
| `CHAT_INTENT_CONFIDENCE_THRESHOLD` / `CHAT_INTENT_CLASSIFY_ALL_TURNS` | 0.75 / false | bộ phân loại app |

Cách chọn model, chạy đánh giá và triển khai dần cho chat/intent/slot/drafting: xem
[định tuyến model AI](AI_MODEL_ROUTING.md).

### 10. Sự cố và lỗi thực tế

| Triệu chứng / lỗi | Nguyên nhân và cách sửa |
| --- | --- |
| `unknown manifest key … "maxLength"` | dùng `maxLen` |
| `unknown key entities[...].storage` / `fields[...].storage` | `storage` chỉ hợp lệ ở root manifest; xóa khỏi entity/field. Draft AI mới được normalize tự động; manifest cũ trong DB được migration V25 sửa tại chỗ |
| `storage: typed is not available in this build` | đổi `storage` sang `jsonb`; bản build này chưa có bảng typed |
| `entity <name> requires label.vi and label.en` | thêm `label` cho entity (schema bắt buộc) |
| `filter op contains requires a string field, got …` | `contains` chỉ dùng cho field `string`; dùng `eq`/`in` cho enum/number |
| `free-form sql in report view is forbidden` | chuyển sang `groupBy`/`metrics`/`filters` |
| `metric sum requires numeric field` | metric chỉ nhận field `number` |
| `orderBy target x is neither a declared field nor metric alias` | `orderBy` phải là field hoặc alias đã khai báo |
| `entity knowledge projection requires rbac.read …` | thêm đủ 3 vai trò vào `rbac.read` |
| `unsupported notify recipient` | chỉ `creator` / `record.<field string trên entity chạy hook>` / `config.<key>` / email |
| `hook target … cannot run as a lifecycle hook` | chỉ 4 loại khối được làm hook; `report`/`registry`... thì không |
| `approval_workitem cannot run onDecide` | chuyển approval sang `onCreate`/`onUpdate` |
| `external_call egress.allowedHosts does not include …` | thêm host (hoặc bỏ egress) và đảm bảo `config.allowedHosts` của bản cài có host đó |
| `inline field … looks like a secret` | đưa secret vào vault, dùng `credentialRef` |
| `App <kind> is already installed` | dùng `POST /apps/{kind}/upgrade` (editor tự gọi khi bấm Install) |
| `Upgrade removes entity/field` / `changes the type` | không được xóa/đổi kiểu phần đã có bản ghi khi nâng cấp |
| `Report views are workspace-admin material` | chuyển vai trò workspace sang admin (tab/API report) |
| `Export is limited to 10000 records` | giới hạn của registry; thu hẹp phạm vi trước khi export |
| Báo cáo trống | view không có `metrics`/`groupBy` và không có dòng → file trắng (đúng như thiết kế) |
| Chat không thấy dữ liệu app | đặt `config.knowledgeBaseId` = KB chat, cập nhật 1 bản ghi, kiểm tra tài liệu ở trạng thái `PROJECTED`; lưu ý top-K không phủ hết — dùng report cho số liệu chính xác |
| Digest bị cũ | connector ghi không phát sự kiện; PATCH một bản ghi để kích hoạt dựng lại |
| Đã đặt KB đích nhưng chat trộn tài liệu app khác | xem giới hạn §7.4 |
| `This record is pending approval; withdraw…` | bản ghi đang `PENDING`: withdraw/rút duyệt trước khi lưu trữ |
| `Archived records must be restored before editing` | gọi `POST …/{recordId}/restore` trước khi sửa |
| `storage: typed…` / `requires label` / `contains…` | xem các dòng đầu bảng §10 |

### 11. Ranh giới và pháp lý (non-goals)

- Không stringify hay thực thi SQL tự do, không Text-to-SQL, không DDL từ manifest.
- Không giữ private key VHMS và không ký số trong process; e-signature là recipe gọi `external_call` tới nhà
  cung cấp bên ngoài; giá trị pháp lý do provider và luật sở tại quyết định (người vận hành tự tham vấn).
- Không tự cài từ bản thảo/template/import; luôn có nút xác nhận.
- Không xác minh mật mã tách rời cho template từ xa; v1 chỉ neo bằng SHA-256 trong envelope/catalog.

### 12. Khôi phục và retention

- **Workspace:** archive là soft-delete, không truy cập được nhưng vẫn giữ membership/slug/tài liệu/app data/KB/index.
  `POST /api/v1/admin/workspaces/{id}/restore` (`ORG_OWNER`) đưa workspace về `ACTIVE`; không cần provisioning hay đánh chỉ mục lại.
- **Bản ghi BizApp:** archive → `ARCHIVED`; `POST …/{recordId}/restore` (quyền `delete` trong manifest) đưa về `ACTIVE`,
  phát `record.restored`, cập nhật lại tài liệu knowledge và digest. Bản ghi `PENDING` phải withdraw trước khi archive.
- **Retention:** `app.retention.workspace-days` / `app.retention.bizapp-record-days` (0 = tắt). Job hằng ngày chỉ
  audit các workspace đủ điều kiện purge, **không tự xóa**; purge bản ghi chỉ chạy khi
  `app.retention.bizapp-record-purge-enabled=true` (mặc định false) và xóa kèm connector job.
- **Legal hold:** `PATCH /api/v1/admin/workspaces/{id}/legal-hold` đặt cờ chặn purge vĩnh viễn cho workspace.
  Purge bản ghi cũng join `tenants`, nên workspace đang bị hold (hoặc đã archive) không bao giờ bị purge bản ghi.
- Dữ liệu archive trước V24 có `archived_at = NULL`, nên không bao giờ là ứng viên purge; chỉ restore thủ công hoặc qua API.
- `TenantStatus.INACTIVE` được giữ chỗ cho chế độ read-only tương lai; bản build này không mở transition nào.
- **Xóa cứng workspace:** chỉ là runbook vận hành có review; FK trải khắp documents/chat/KB/workflow/audit nên không auto-purge.

---

## BizApps — the installable business app platform

### 0. Mental model (TL;DR)

A BizApp is a **declarative business application** running directly on the platform's headless services —
no dedicated backend. All behavior is described by **one JSON manifest** under the frozen `bizapp.v1`
contract:

```mermaid
flowchart LR
    M[Manifest bizapp.v1<br/>entities · blocks · reports · rbac · ui] -->|install| I[Freeze manifest<br/>+ deploy BPMN process]
    I --> R[Runtime<br/>registry / form / timeline / report / assistant]
    R -->|hooks after commit| H[Flowable approval · Notify · Webhook · Connector]
    R -->|projection| K[KB `bizapp-&lt;kind&gt;`<br/>record docs + digest]
    K --> C[Chat with citations]
```

| Surface | Route | Who |
| --- | --- | --- |
| Marketplace + owner console (install/templates/drafts/credentials) | `/admin/apps`, `/admin/apps/compose` | Organization owner |
| Runtime views (registry/form/timeline/report/assistant) | `/apps`, `/apps/{kind}` | Per manifest `rbac` |
| Conversational BizApps (list/install/suggest/create/records) | Workspace chat | Per role |

> **Deployment model:** the OSS self-host build runs **one organization per installation**; `org` in the schema/API is the implicit tenant, not a multi-org UI. The template catalog has **built-in** entries (JSON in the codebase) and **company** entries (that organization's DB).

### 1. Quickstart (10 minutes)

1. Open `/admin/apps/compose`; set Vi/En name, edit the default `records` entity (fields, types,
   required, PII). The composer exposes friendly settings only; deep field options (`maxLen`, `format`,
   `options`, `optionLabels`, entity `knowledge`) go through the **JSON tab** or the inspector's
   **Additional settings**.
2. Add executable blocks (Approval with 1/2-level preset, Report, Notify, Webhook, External Call).
   Drag entity → block to create a hook (`onCreate`/`onUpdate`/`onDecide` — pick in the edge dialog).
3. Watch the validation panel (debounced 400ms, `POST /api/v1/bizapps/validate`); green = installable,
   red = readable error list.
4. **Save draft** or **Install** (auto-upgrades when the same `kind` is already active). Install freezes
   the manifest, deploys BPMN, then routes to `/apps/{kind}`.
5. Create records, submit for approval, run reports, chat with the assistant.

### 2. Manifest reference (`bizapp.v1`)

Machine-readable schema: `contracts/bizapp/bizapp.v1.schema.json` (`additionalProperties: false`).
Unknown keys are rejected with corrective hints (`maxLength` → "the contract key is `maxLen`").

#### 2.1 Top level

| Key | Shape | Default | Notes |
| --- | --- | --- | --- |
| `schema` | const `"bizapp.v1"` | — | required |
| `kind` | `[a-z][a-z0-9-]{1,31}` | — | unique per workspace; reinstall of same kind → `409` |
| `name` | `{vi, en}` printable ≤200 chars | — | required |
| `version` | int ≥1 | 1 | upgrade takes `max(current+1, requested)` |
| `icon` | string ≤64 | — | label; `/apps` falls back to emoji |
| `storage` | `jsonb` | `jsonb` | `typed` is rejected in this build (every app uses `bizapp_records`) |
| `rbac` | `read/write/delete/approve/submit` → role arrays | see §2.3 | |
| `entities` | 1..20 | — | required |
| `blocks` | ≤40 | — | |
| `conversational` | `{triggers[≤50 ×≤200], description≤2000}` | — | semantic routing hints |
| `ui` | `{views[≤20], layout}` | — | runtime order + canvas coordinates |

#### 2.2 `entities` & `fields`

**Entity**: `name` (`[a-z][a-z0-9_]{0,63}`, unique), `label {vi,en}` (required — the validator rejects an entity without one),
`fields` 1..40, `knowledge`, `hooks`.

**Field**:

| Key | Values | Default | Notes |
| --- | --- | --- | --- |
| `name` | lowercase `[a-z][a-z0-9_]{0,63}` | — | unique per entity; runtime tolerates uppercase but the frozen schema is the contract |
| `type` | `string` `number` `boolean` `enum` `date` `ref` | — | |
| `entity` | declared entity name | — | required for `ref` and must exist |
| `required` | bool | false | blocks create when missing; blocks update only when the key is present but blank |
| `maxLen` | 1..2000 | 1024 for `string` | **not** `maxLength` |
| `format` | `email` `uri` `phone` | — | only `email` is validated server-side today |
| `options` | string list | — | required for `enum`; write matches case-insensitively |
| `optionLabels` | option → `{label, aliases}` | — | display label + slot-extraction aliases |
| `pii` | bool | false | never projected to the knowledge base |
| `label` | `{vi,en}` | field name | used by registry/form/export/assistant |

`date` accepts ISO `yyyy-MM-dd`, `yyyy-MM-ddTHH:mm`, `d/m/y`, and natural language ("today", "tomorrow",
"next Friday", Vietnamese weekday names); times normalize to `HH:mm`.

#### 2.3 How `rbac` is enforced

| Capability | Default when omitted | Notes |
| --- | --- | --- |
| `read` | every workspace role | `ORG_OWNER` always reads |
| `write` | workspace admins only | list `MEMBER` to grant write |
| `delete` | workspace admins only | |
| `approve` | workspace admins only | `candidateRole` must be inside this list when declared |
| `submit` | falls back to `write` | or its own list |

The runtime re-derives the manifest role per request; client-provided roles are never trusted. Runtime also
honors `entities[].rbac.write/submit` (emitted by some AI drafts), but the top-level `rbac` is canonical —
the frozen schema does not declare per-entity `rbac`.

#### 2.4 `knowledge`

```json
"knowledge": { "project": true, "titleField": "name", "fields": ["name", "amount", "stage"] }
```

Valid only when `rbac.read` covers all three roles (fail-closed); `titleField`/`fields` must exist; `pii`
fields are rejected. Details in §7.

#### 2.5 `hooks`

| Hook | Runs after | Notes |
| --- | --- | --- |
| `onCreate` | record created | approval → auto-submit |
| `onUpdate` | record patched | same; a `PENDING` record is not restarted |
| `onDecide` | approval decided | always best-effort; must not target `approval_workitem` |

Targets are hook-capable block ids (`approval_workitem`, `notify`, `webhook_out`, `external_call`) or
`webhook_out:<event>` where the event is declared by a `webhook_out` block. Per event the order is
**approval → external_call → notify/webhook/custom**, so a failure that would roll the transaction back
is never announced afterwards. `external_call` only enqueues a durable job inside the transaction (provider
failures belong to the worker's retry loop); `notify`/`webhook_out` failures are audited and never abort the
write; `onDecide` is fully best-effort. `BIZAPP_HOOK_EXECUTED`/`BIZAPP_HOOK_FAILED` is audited per target.

#### 2.6 `ui`

`views` is an array of `"<prefix>:<target>"` (≤20 entries, each ≤100 chars):

| Prefix | Target | Renders |
| --- | --- | --- |
| `registry:` | entity | paged record table, create/edit/archive/submit/export |
| `form:` | entity | same with the create dialog pre-opened |
| `timeline:` | entity | card feed of recent records |
| `report:` | view name | report table (workspace admin) |
| `assistant:` | `assistant` block id | in-app chat panel |

`layout` stores `{x,y}` (±10000) per entity/block id. Invalid references are rejected at validation.

#### 2.7 `conversational`

`triggers` and `description` help the semantic router pick this app when the message does not name the kind.
Avoid generic phrases such as "manage".

#### 2.8 Minimal example

```json
{
  "schema": "bizapp.v1",
  "kind": "asset-request",
  "name": { "vi": "Yêu cầu tài sản", "en": "Asset request" },
  "version": 1,
  "storage": "jsonb",
  "rbac": {
    "read": ["ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER"],
    "write": ["WORKSPACE_ADMIN", "MEMBER"],
    "approve": ["WORKSPACE_ADMIN"]
  },
  "entities": [{
    "name": "requests",
    "label": { "vi": "Yêu cầu", "en": "Requests" },
    "fields": [
      { "name": "title", "type": "string", "required": true, "maxLen": 200 },
      { "name": "amount", "type": "number" },
      { "name": "stage", "type": "enum", "options": ["NEW", "APPROVED", "REJECTED"] }
    ],
    "hooks": { "onCreate": ["approval1"] }
  }],
  "blocks": [
    { "id": "approval1", "type": "approval_workitem",
      "params": { "bpmnTemplate": "processes/bizapp-single-approval.bpmn20.xml",
                  "candidateRole": "WORKSPACE_ADMIN",
                  "sla": { "reminder": "PT24H", "escalation": "PT48H" } } },
    { "id": "notify1", "type": "notify",
      "params": { "to": ["creator"], "title": "New request", "subject": "Request {{record.title}}" } }
  ],
  "conversational": { "triggers": ["asset request", "yêu cầu tài sản"] },
  "ui": { "views": ["registry:requests", "form:requests", "timeline:requests"] }
}
```

### 3. Blocks catalog

Rules: block `id` unique; hooks target only `approval_workitem` / `notify` / `webhook_out` /
`external_call`. `report`/`knowledge_base`/`assistant` are functional but **not** hook-targetable.
The runtime tabs `registry:`/`form:`/`timeline:` live in `ui.views`, not in the block list.

#### 3.1 `approval_workitem` (at most 1 per manifest)

- `bpmnTemplate` must match `processes/<name>.bpmn20.xml`. Bundled: `bizapp-single-approval.bpmn20.xml`,
  `bizapp-two-level-approval.bpmn20.xml`, `eoffice-document-approval.bpmn20.xml`. A missing custom template
  **falls back** to the generic single-approval process (install still succeeds).
- `candidateRole` (default `WORKSPACE_ADMIN`) must be granted by `rbac.approve` when that list is declared.
- `sla.reminder` (default `PT24H`) / `sla.escalation` (default `PT48H`), ISO-8601 strings.
- Install deploys one Flowable process per workspace, key `bizapp-<kind>`, with variables `recordId`,
  `entity`, `appKind`, `workspaceId`, `initiatorId`, `candidateRole`, `reminderDuration`,
  `escalationDuration`.
- Claim/complete run through the **generic engine task inbox**; the terminal delegate writes
  `runtime.approvalStatus = APPROVED | REJECTED` plus `processId`.

#### 3.2 `notify`

- `params.to` (required, list): `creator` (the **record's** author, not the acting user) |
  `record.<emailField>` (the string field must be declared on **every entity whose hook targets this block**) | `config.<key>` | literal address.
- `subject`/`title` support `{{record.<field>}}` / `{{<field>}}` placeholders.
- Delivers HTML via the organization's email channel; delivery failures are audited and never roll the
  write back; unresolvable recipients are skipped with a warning.

#### 3.3 `webhook_out`

- `params.events` (required): `record.created|updated|archived|restored|submitted|decided` or custom
  `[a-z0-9][a-z0-9._-]{1,62}` kinds.
- The record service publishes an event only when some block declares it; a hook target
  `webhook_out:<event>` publishes `bizapp.<kind>.<event>` immediately at hook time (best-effort).
- Signed HMAC + fan-out happens in the integration module after commit.

#### 3.4 `external_call`

| Param | Domain | Default | Notes |
| --- | --- | --- | --- |
| `protocol` | `http` \| `mqtt` | `http` | |
| `url` | absolute URL | — | required for `http`; HTTPS unless `allow-http` |
| `method` | `GET|HEAD|POST|PUT|PATCH|DELETE` | `POST` | `GET/HEAD` send **no body** (GET-only APIs must not receive a smuggled POST) |
| `bodyTemplate` | JSON string | — | POST/PUT/PATCH only; `{{record.<field>}}` |
| `responseMap` | key → `/jsonPointer` | — | ≤8 entries; written to `runtime.external.<blockId>` |
| `credentialRef` | vault entry name/id | — | required for HTTP header auth; MQTT credential when set |
| `auth` | `{type: header|hmac|oauth2, …}` | `header` | `hmac` needs `secretRef` (+ optional `header`/`timestampHeader`, default `X-OpenVie-Signature`/`X-OpenVie-Timestamp`); `oauth2` needs https `tokenUrl` + `credentialRef` (`client_id:client_secret`, token cache refreshed at ~80% TTL) |
| `sink` | `{type: field\|record, entity, mapping, dedupeKey}` | `field` | `field` updates the source record (exactly one hook source entity or `schedule.entity`); `record` requires a declared `sink.entity` and `dedupeKey` 1..5 ⊆ `mapping` keys (idempotent upsert) |
| `mapping` | `"<entityField>": "/path"` | — | every target field must be declared |
| `schedule` | `{every, entity, select, limit}` | — | `every` PT30S..P7D; `select` ≤5 declared scalar fields; `limit` 1..500 (50) |
| `timeout` | `{connectMs, readMs}` | 10000/15000 | 100..60000 ms |
| `retry` | `{maxAttempts, backoff}` | 3 / PT10S | 1..10 attempts, PT1S..PT10M backoff, exponential ×2 capped at 10 minutes |
| `egress.allowedHosts` | host list | — | must contain the target host; only **narrows** the installation list |

Security boundaries: the installation must carry `config.allowedHosts` (missing → the connector refuses to
run). Inline secrets (keys ending `password/secret/token/api_key`) are rejected; PEM private keys cannot be
stored; responses are capped at 256 KiB; redirects disabled; DNS/SSRF check blocks loopback/private/
link-local/metadata targets unless `allow-private-hosts`. Each run is a durable job
(`PENDING/RUNNING/SUCCEEDED/DEAD`) audited and metered; retryable failures back off exponentially
(cap 10 min) until `maxAttempts` then `DEAD`.

MQTT: one bounded subscribe per job (`qos` 0|1, `maxMessages` 1..100, `readMs`), always disconnects; the
credential uses the vault `mqtt` format (`username:password`); `tcp://` brokers require
`BIZAPPS_OUTBOUND_ALLOW_HTTP`.

#### 3.5 `knowledge_base` & `assistant`

- `knowledge_base`: optional `params.graphExtraction`; creates/returns the dedicated `bizapp-<kind>` KB.
- `assistant`: `params.instructions` is displayed as the assistant panel's guidance; execution is a no-op;
  the tab opens a normal workspace chat session. `params.knowledgeBaseBlock` only draws the canvas edge.

### 4. Declarative report views (replaces raw SQL)

Report views live in a `report` block (`params.views`). Free-form `sql` is forbidden at validation.
View fields:

| Key | Meaning |
| --- | --- |
| `name`, `entity` | required; entity must exist |
| `title` | display metadata (composer produces it) |
| `fields[]` | column list for **list views** (only used when there is no `groupBy`/`metrics`) |
| `groupBy[]` | declared columns to group by |
| `metrics[]` | `{fn: count\|sum\|avg\|min\|max, field?, as}` — `sum/avg` require a `number` field; `count` may omit it |
| `filters[]` | `{field, op, value? / values? / param?}` — `op`: `eq ne in gt gte lt lte contains isnull` (`contains` only on `string` fields); `param` binds a runtime value |
| `orderBy[]` | `{by, dir: asc\|desc}` — `by` must be a declared field or a groupBy/metric alias |
| `limit` | 1..500 (default 100); SQL always `LIMIT`s to this cap |
| `engine` | `postgres` (JSONB columns via `payload->>'field'`, numeric cast `::numeric`) or `lakehouse` (DuckDB) |

The compiler always injects `org_id`, `tenant_id`, JSONB `app_kind`/`entity` plus `status='ACTIVE'`, binds
every user value as a named parameter, and rejects reserved params (`orgId/tenantId/appKind/entityName`).
The source table is fixed by the storage layer; a manifest can never address another table.

Permissions: the report endpoint and the report tab are **workspace-admin only**; the runtime table shows
the **first 6 columns** while export returns **all columns and rows**.

### 5. Runtime views, records, approvals

| View | Behavior |
| --- | --- |
| `registry:` | 20 records per page (`updated_at desc`), refresh, CSV/XLSX export, create, edit, archive (soft), **restore**, **Show archived** toggle, submit (only when the app has an approval block) |
| `form:` | same as registry with the create dialog pre-opened; the form is generated from the manifest so undeclared fields cannot be typed (the API also rejects them) |
| `timeline:` | newest records as cards, first three fields plus a status badge |
| `report:` | report table (admin only), refresh, export |
| `assistant:` | in-app chat panel (§7) |

Record state:

| Field | Values |
| --- | --- |
| `status` | `ACTIVE` \| `ARCHIVED` (soft; no client hard-delete endpoint; retention purge is a server-side job) |
| `archivedAt` / `archivedBy` | archive timestamp/actor; cleared on restore |
| `runtime.approvalStatus` | `PENDING` \| `APPROVED` \| `REJECTED`; `runtime.processId` while running |

A `PENDING` record **cannot be archived** (409; withdraw first). An `ARCHIVED` record **cannot be edited**
(400; restore first). Restore flips to `ACTIVE`, keeps payload/runtime (decision history stays attributable),
publishes `record.restored` and re-projects the knowledge document (archive deleted it).

Record APIs are fenced by manifest `rbac` plus the manifest role re-derived per request; unknown entities are
404, unknown payload fields 400. Editing a `PENDING` record preserves its process state; editing after a
decision clears it.

### 6. Visual composer

Routes: `/admin/apps/compose` (new app; `?template=<platform:<kind>|<uuid>>` starts from a template manifest)
and `/admin/apps/compose/{draftId}`.

Header: back, rename (vi/en title), schema · kind · version · workspace badge, Auto Layout (canvas only),
Save draft, Install (disabled while validation fails), theme + language switcher.

Tabs:

| Tab | Content |
| --- | --- |
| Canvas | palette (left), React Flow canvas, inspector (right) |
| List | entity/block cards: select (opens inspector), delete, add entity |
| JSON | live manifest editor with Format/Copy and instant syntax errors; edits sync into the graph |

Canvas edges:

| Edge | Meaning |
| --- | --- |
| entity → entity (`ref` field) | derived, read-only |
| entity → block (`hook`) | drag to create; each repeated drag adds the next event (`onCreate` → `onUpdate` → `onDecide`); click to change/delete |
| `report` block → entity | derived from views; removing a view from the edge dialog **deletes the view** |
| `external_call` → entity (`schedule`) | dashed, read-only; edit the schedule in the inspector |
| assistant → knowledge base | derived when `params.knowledgeBaseBlock` points at a block |

**Block registry:** `contracts/bizapp/blocks.v1.json` is the single source for types, hookability, palette order and defaults; the backend and composer read the same file. Add a block type there first.

Palette adds one entity or one of seven blocks with safe defaults (report creates a first view for the first
entity, webhook selects `record.created`/`record.updated`, notify defaults to `["creator"]`, approval uses
the single-process preset with `WORKSPACE_ADMIN`, knowledge_base needs no setup and is selectable from the
assistant inspector; adding/editing an assistant ensures its `assistant:<id>` tab in `ui.views`).

Inspector highlights:

- Entity: name, EN label, fields (name/type/required/PII, add/remove). Deep field settings (vi label,
  `maxLen`, `format`, `options`, `optionLabels`, `knowledge`) live in the JSON tab.
- Approval: process preset (single/two-level) + approver role; the two-level flow is explained inline.
- External call: URL, credentialRef; remaining params surface under "Additional settings" (scalar/list/object
  editors for keys the UI does not specialize).
- Notify: recipients, Title, Subject.
- Report: **report builder** (title, entity, list vs summary, fields, metrics + unique aliases, filters,
  sort, limit 1..500; slug name from title; changing the entity resets grouping).
- Webhook: checkbox list of standard events; Knowledge base: "automatic" note.

Validation panel: debounced 400ms `POST /bizapps/validate`; Install stays disabled while red. The response
also carries `issues: [{path, message}]`; the canvas maps `entities.<name>`/`blocks.<id>` to a red node badge and
tooltip while the panel keeps the full list.

Actions: Save draft (`PUT /drafts/{id}`, or `PUT /drafts/new` for the first save), Save as Template (dialog
for name/summary → `POST /marketplace/bizapps`), Install — an already active kind **upgrades in place**
(version bumped, records preserved). Installed-app cards in `/admin/apps` also offer **Save as template**
using the frozen manifest.

**Composer shortcuts:** `Ctrl/Cmd+S` saves the draft, `Ctrl/Cmd+Z` undoes, `Shift+Ctrl/Cmd+Z`/`Ctrl+Y` redoes, `Delete`/`Backspace` removes the selected node/edge (and prunes dependent views/reports/hooks), `Esc` closes the inspector or edge dialog.

### 7. Knowledge projection & assistant

- **Record docs:** entities with `knowledge.project` mirror each record to
  `bizapp__<kind>__<entity>__<recordId>.md` (declared non-`pii` fields only), `document_kind=BIZAPP_RECORD`,
  `source_ref = bizapp:<kind>:<entity>`.
- **Digest:** `bizapp__<kind>__digest.md` renders every declared report view as a Markdown table and is
  rebuilt with a 30-second debounce (`BIZAPPS_PROJECTION_DEBOUNCE_MS`) after record lifecycle events. Record
  events are published **unconditionally** for every API record write; whether a `webhook_out` block exists
  only affects **webhook delivery**, never projection/CDC. Install
  and uninstall write no documents, so they never bump `search_revision` (no `409 KNOWLEDGE_BASE_CHANGED`
  while a chat is streaming).
- **Target KB:** the default is a dedicated `bizapp-<kind>` KB, but chat retrieves only the session chatbot's
  KB (normally the workspace default). Point the installation `config.knowledgeBaseId` at the workspace
  default (`PATCH /bizapps/{kind}/config`) and touch a record to re-project.
- **System-document filtering:** `document_kind` is carried into the Qdrant payload/index; normal chat
  excludes `BIZAPP_RECORD`/`BIZAPP_DIGEST` by default, while app-scoped chat can retrieve records when
  configured.
- **Assistant tab (`assistant:`):** opens a normal workspace chat session; the block's `instructions` show as
  guidance. App scoping depends on the router resolving the app from the message content.
- **Grounding reality:** aggregate answers that must be exact should come from report views (report tab or
  export). Chat answers are top-K retrieval and never scan all records.

Known limitations of the current build:

1. Retrieval is top-K; coverage is not guaranteed; the digest is a text copy of declared views.
2. Writes made through **connectors** do not emit lifecycle events, so the digest/projection can lag until a
   record is updated through the record API.
3. The app assistant tab is not automatically scoped to its app; questions that do not name the app can mix
   documents from other apps.

**Workspace-level isolation:** to keep apps from sharing data, create a dedicated workspace, install only one
app, enable `knowledge.project` on the entities to sync (with `rbac.read` covering all three roles), point
`config.knowledgeBaseId` at that workspace's default KB, and create an API key bound to that workspace
(`POST /api/v1/admin/api-keys`). Chat and API-key traffic then see only that workspace's KB; this is
tenant/workspace isolation, not app scoping — every document in that KB remains retrievable.

### 8. API surface

All paths below are relative to `/api/v1`.

| Method & path | Permission | Description |
| --- | --- | --- |
| `GET /bizapps`, `GET /bizapps/{kind}` | members | installed apps + frozen manifest |
| `GET /bizapps/installed-summary`, `GET /bizapps/installed-anywhere` | members | chat/catalog summary; whether any workspace has an app installed |
| `GET /bizapp-data/{kind}/{entity}` | `rbac.read` | paged records (`page`, `size≤100`, `includeArchived`) |
| `POST /bizapp-data/{kind}/{entity}` | `write` | create a record |
| `GET /bizapp-data/{kind}/{entity}/{recordId}` | `rbac.read` | read one record |
| `PATCH /bizapp-data/{kind}/{entity}/{recordId}` | `write` | patch a record |
| `DELETE /bizapp-data/{kind}/{entity}/{recordId}` | `delete` | archive a record |
| `POST …/{recordId}/restore` | `delete` | restore an archived record |
| `POST …/{recordId}/submit` | `submit` | start approval |
| `POST /bizapps/{kind}/reports/{view}` | workspace admin | run the view (body `{"params": {…}}`) |
| `POST /bizapps/{kind}/reports/{view}/export` | workspace admin | full CSV/XLSX |
| `GET /bizapps/{kind}/entities/{entity}/export` | `rbac.read` | CSV/XLSX records (ACTIVE; `includeArchived=true` adds archived rows, 10 000 cap) |
| `PATCH /bizapps/{kind}/config` | ORG_OWNER | replace install config (`allowedHosts`, `knowledgeBaseId`, …) |
| `POST /bizapps/validate` | ORG_OWNER | stateless manifest validation |
| `POST /bizapps/drafts/generate`, `POST /bizapps/drafts/from-chat` | ORG_OWNER | AI drafting / draft from last 20 chat turns |
| `GET /bizapps/drafts`, `GET/PUT/DELETE /bizapps/drafts/{id}`, `PUT /bizapps/drafts/new` | ORG_OWNER | draft CRUD |
| `GET /marketplace/bizapps`, `GET /marketplace/bizapps/{templateRef}` | members | built-in + company catalog |
| `POST /apps/install`, `POST /apps/{kind}/upgrade`, `DELETE /apps/{kind}` | ORG_OWNER | install / upgrade / soft uninstall |
| `POST /marketplace/bizapps`, `DELETE /marketplace/bizapps/{templateId}` | ORG_OWNER | save / delete company template |
| `GET /marketplace/bizapps/{templateRef}/export`, `GET /marketplace/catalog` | members | read/export templates and catalog |
| `POST /marketplace/bizapps/import`, `POST /marketplace/bizapps/{templateRef}/remix` | ORG_OWNER | import/remix templates |
| `GET/POST /bizapp-credentials`, `DELETE /bizapp-credentials/{credentialId}` | ORG_OWNER | credential vault |

`templateRef` is `platform:<kind>` (crm, booking, signature-request) or a company template UUID.

### 9. Configuration

| Key (env) | Default | Meaning |
| --- | --- | --- |
| `BIZAPPS_ENABLED` | true | kill-switch: false turns every `/bizapp*` route into 404 |
| `BIZAPP_ENC_KEY` | — | required to store credentials; `openssl rand -base64 32` |
| `BIZAPPS_OUTBOUND_ALLOW_HTTP` | false | allow plain `http://` (dev only) |
| `BIZAPPS_OUTBOUND_ALLOW_PRIVATE_HOSTS` | false | dev escape for loopback/private hosts (mock servers) |
| `BIZAPPS_AI_DRAFTING_ENABLED` | true | disable → AI drafting answers 404 |
| `BIZAPPS_AI_DRAFT_MODEL` / `_FALLBACK_MODEL` | empty | `CompleteJson` model override; empty = AI service default |
| `BIZAPPS_PROJECTION_DEBOUNCE_MS` | 30000 | digest debounce window |
| `BIZAPPS_REMOTE_CATALOG_ENABLED` / `_HOSTS` | false / empty | host allowlist for the remote catalog and URL imports |
| connectors (`enabled` true, `scan-delay-ms` 15000, `worker-delay-ms` 5000, `batch-size` 20) | | scheduler/worker cadence |
| `app.retention.workspace-days` / `bizapp-record-days` | 0 (disabled) | purge-eligibility window; workspaces are report-only |
| `app.retention.bizapp-record-purge-enabled` | false | enable hard deletion of expired archived records |
| `app.retention.max-records-per-run` | 200 | purge cap per sweep |
| `app.retention.scan-delay-ms` / `initial-delay-ms` | 86400000 / 300000 | retention sweep cadence |
| `CHAT_BIZAPP_ACTIONS_ENABLED` / `CHAT_BIZAPP_RECORDS_ENABLED` | true | chat kill-switches |
| `CHAT_INTENT_CONFIDENCE_THRESHOLD` / `CHAT_INTENT_CLASSIFY_ALL_TURNS` | 0.75 / false | app intent classifier |

For model selection, evaluation, and rollout for chat, intent, slots, and drafting, see
[AI model routing](AI_MODEL_ROUTING.md).

### 10. Troubleshooting (real error messages)

| Symptom / message | Cause & fix |
| --- | --- |
| `unknown manifest key … "maxLength"` | use `maxLen` |
| `unknown key entities[...].storage` / `fields[...].storage` | `storage` is only valid at the manifest root; remove it from entities/fields. New AI drafts are normalized automatically; legacy stored manifests are repaired by migration V25 |
| `storage: typed is not available in this build` | switch `storage` to `jsonb`; no typed table ships today |
| `entity <name> requires label.vi and label.en` | add an entity `label` (schema requires it) |
| `filter op contains requires a string field, got …` | `contains` is string-only; use `eq`/`in` for enum/number |
| `free-form sql in report view is forbidden` | express it with `groupBy/metrics/filters` |
| `metric sum requires numeric field` | metrics only accept `number` fields |
| `orderBy target x is neither a declared field nor metric alias` | orderBy must be a declared field or alias |
| `entity knowledge projection requires rbac.read …` | grant all three roles in `rbac.read` |
| `unsupported notify recipient` | only `creator` / `record.<string field on the hooking entity>` / `config.<key>` / an email |
| `hook target … cannot run as a lifecycle hook` | only the four hookable block types |
| `approval_workitem cannot run onDecide` | move approval to `onCreate`/`onUpdate` |
| `external_call egress.allowedHosts does not include …` | add the host (or drop `egress`) and ensure the installation `config.allowedHosts` contains it |
| `inline field … looks like a secret` | move the secret to the vault and use `credentialRef` |
| `App <kind> is already installed` | use `POST /apps/{kind}/upgrade` (the composer's Install button does this) |
| `Upgrade removes entity/field` / `changes the type` | upgrades cannot drop or retype data that records already carry |
| `Report views are workspace-admin material` | the report tab/API needs the workbook admin role |
| `Export is limited to 10000 records` | registry cap; narrow the scope before exporting |
| empty report export | the view returned no rows and no derivable headers → the file is intentionally empty |
| chat cannot see app data | set `config.knowledgeBaseId` to the chat KB, touch a record, verify `PROJECTED`; remember top-K is not exhaustive — use reports for exact numbers |
| digest is stale | connector writes emit no events; patch a record to trigger a rebuild |
| chat mixes documents from another app | see limitation §7 |
| `This record is pending approval; withdraw…` | the record is `PENDING`: withdraw the approval before archiving |
| `Archived records must be restored before editing` | call `POST …/{recordId}/restore` first |
| `storage: typed…` / `requires label` / `contains…` | see the top rows of §10 |

### 11. Boundaries & legal (non-goals)

- No string SQL execution, no Text-to-SQL, no DDL derived from manifests.
- No in-process signature/keys custody; e-signature is a recipe delegating to an external provider through
  `external_call`; legal validity belongs to the provider and local law (operators seek their own counsel).
- No auto-install from drafts/templates/imports; every install is an explicit confirmation.
- No detached cryptographic verification for remote templates; v1 anchors trust in the SHA-256 hash inside
  the template envelope/catalog index.

### 12. Restore & retention

- **Workspace:** archive is a soft delete: unreachable, but memberships/slug/documents/app data/KB/index are
  retained. `POST /api/v1/admin/workspaces/{id}/restore` (ORG_OWNER) flips it back to `ACTIVE`; no
  provisioning or re-index runs.
- **BizApp record:** archive sets `ARCHIVED`; `POST …/{recordId}/restore` (manifest `delete`) flips it back to
  `ACTIVE`, publishes `record.restored` and re-projects the knowledge doc/digest. A `PENDING` record must be
  withdrawn before it can be archived.
- **Retention:** `app.retention.workspace-days` / `app.retention.bizapp-record-days` (0 = disabled). The daily
  sweep audits purge-eligible workspaces but never deletes them; record purge runs only when
  `app.retention.bizapp-record-purge-enabled=true` (default false) and removes connector jobs with the row.
- **Legal hold:** `PATCH /api/v1/admin/workspaces/{id}/legal-hold` blocks purge for that workspace regardless
  of age. The record purge query also joins `tenants`, so a held (or archived) workspace never loses records.
- **Rows archived before V24** have `archived_at = NULL`, so they are never purge candidates; restore them
  through the API/UI.
- `TenantStatus.INACTIVE` is reserved for a future read-only pause; this build exposes no transition to it.
- **Workspace hard delete:** reviewed ops runbook only; FKs span documents/chat/KB/workflow/audit, so it is
  never auto-purged.
