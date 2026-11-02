# BizApps — nền tảng ứng dụng kinh doanh cài được (Tier 3)

[Danh mục tài liệu](README.md) · [Kiến trúc](ARCHITECTURE.md) · [Tích hợp](INTEGRATION.md) · [Workflow](WORKFLOW.md) · [DX-OS](DX_OS.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#bizapps--nền-tảng-ứng-dụng-kinh-doanh--tiếng-việt) · [English](#bizapps--the-installable-business-app-platform)

---

## BizApps — nền tảng ứng dụng kinh doanh — tiếng Việt

BizApp là **ứng dụng kinh doanh khai báo** ("template") cài được vào một workspace,
chạy trên dịch vụ nền tảng hiện hữu thay vì backend riêng: kho bản ghi JSONB
theo workspace, phê duyệt qua engine Flowable, email qua kênh SMTP của tổ chức,
webhook HMAC có chữ ký, và HTTP outbound với vault thông tin xác thực riêng. Mẫu đóng gói sẵn:
**CRM Lite** (quản lý khách hàng tiềm năng) và **Booking** (đặt lịch hẹn, có vòng
duyệt xác nhận). Thiết kế bám mô hình Flowise: schema + validator chặn trước,
marketplace hai tầng (platform + do tổ chức tự tạo), node = block gắn dịch vụ.

### 1. Manifest `bizapp.v1`

Mọi BizApp định nghĩa bằng một JSON (`schema: "bizapp.v1"`):

| Mục | Ý nghĩa |
| --- | --- |
| `kind` | key ổn định của app (`crm`, `booking`), dùng mọi nơi từ URL tới webhook |
| `entities` | kho dữ liệu khai báo: `fields[]` (`string/number/boolean/enum/date`, `required`, `maxLen`, `format: email`), `hooks` |
| `blocks` | khối tác nghiệp: `approval_workitem`, `notify`, `webhook_out`, `external_call`, `report`, `registry`, `form`, `timeline` |
| `rbac` | `read/write/delete/approve` → vai trò được phép (`ORG_OWNER`, `WORKSPACE_ADMIN`, `MEMBER`); thiếu `write` = chỉ admin |
| `ui` | thứ tự view tại runtime: `registry:<entity>`, `form:<entity>`, `timeline:<entity>`, `report:<view>`, `assistant:<block>` |

**Validator** (`BizAppManifestValidator`) là cổng bảo mật duy nhất, chạy chặt ở
*install*, *save template* và *upgrade*:

- SQL report phải là **đúng một câu SELECT**, cấm `UNION`/`INSERT`/`UPDATE`/`DROP`…,
  cấm `${…}` nội suy, chỉ cho phép named bind `:tenant`/`:app`;
- key trông giống secret (`password`, `secret`, `token`, `apiKey`…) bị từ chối — chỉ cho phép
  `credentialRef` trỏ vào vault của tổ chức;
- `external_call` bắt buộc `credentialRef`, không được nội suy secret;
- `bpmnTemplate` gắt trong `processes/<name>.bpmn20.xml` (cấm traversal `../`);
- hook chỉ trỏ tới block id hoặc `webhook_out:<event>`; mọi tham chiếu `ui` phải
  giải được (entity/report view/block).

### 2. Vòng đời: cài → dùng → gỡ

| Việc | Route (chỉ `ORG_OWNER`) |
| --- | --- |
| Xem marketplace (platform + của tổ chức) | `GET /api/v1/marketplace/bizapps` |
| Cài 1 app (`{"templateRef":"platform:crm"}`) | `POST /api/v1/apps/install` |
| Gỡ (soft: giữ nguyên records) | `DELETE /api/v1/apps/{kind}` |
| Xem cấu hình app + manifest đóng băng | `GET /api/v1/bizapps` · `GET /api/v1/bizapps/{kind}` |
| Đổi config org-level của app | `PATCH /api/v1/bizapps/{kind}/config` |
| Lưu app đang chạy thành template của tổ chức | `POST /api/v1/marketplace/bizapps` |
| Xoá template của tổ chức | `DELETE /api/v1/marketplace/bizapps/{id}` |

Cài đặt: validator chặn trước → **manifest được "đóng băng"** vào bảng
`bizapp_installations` (sửa template sau không ảnh hưởng app đang chạy; nâng cấp =
cài lại) → nếu app có block `approval_workitem`, BPMN
`processes/bizapp-single-approval.bpmn20.xml` được **deploy qua
`WorkflowRuntimeApi`** với tên `bizapp-<kind>` → ghi audit `BIZAPP_APP_INSTALLED`.
Gỡ là soft-delete (`status=DISABLED`): records + audit còn nguyên.

### 3. Kho bản ghi (record store)

Một bảng chung `bizapp_records` (JSONB `payload` + `runtime`) phục vụ mọi app,
phân trang theo workspace + app + entity:

| Việc | Route |
| --- | --- |
| Danh sách records | `GET /api/v1/bizapp-data/{kind}/{entity}?includeArchived=&page=&size=` |
| Tạo / xem / sửa 1 record | `POST` / `GET` / `PATCH /api/v1/bizapp-data/{kind}/{entity}[/{recordId}]` |
| Lưu trữ 1 record | `DELETE /api/v1/bizapp-data/{kind}/{entity}/{recordId}` |
| Chạy view report | `POST /api/v1/bizapps/{kind}/reports/{viewName}` (chỉ admin) |
| Nộp record vào vòng duyệt | `POST /api/v1/bizapp-data/{kind}/{entity}/{recordId}/submit` |

- **Payload được validate chặt**: field nằm ngoài manifest bị từ chối → 400; thiếu field
  bắt buộc → 400; `email`, `date`, `enum` kiểm tra kiểu.
- **`runtime`** (không phải dữ liệu user) chứa `processId`, `approvalStatus`
  (`PENDING/RUNNING → APPROVED | REJECTED`), quyết định gần nhất.
- **RBAC**: mọi bước ghi đều chạy qua lược đồ của manifest + vai trò
  workspace giải lại mỗi request (không tin client).

### 4. Phê duyệt qua engine Flowable

App có block `approval_workitem` tức có nút "Nộp duyệt": `submit` khởi động process
đã deploy (candidate group lấy từ `candidateRole`). Được duyệt/từ chối bằng
**inbox tác vụ tổng quát** `/api/v1/workflow/engine/tasks` (`decision =
APPROVE|REJECT`, `decidedBy`). Cuối process, delegate
`bizAppRecordDecisionDelegate` ghi kết quả về `runtime` trong cùng transaction
và sự kiện `bizapp.<kind>.record.decided` đi vào webhook outbox sau commit
(hoàn toàn tách khỏi luồng tài liệu — task BizApp không thuộc miền phê duyệt
tài liệu nên vẫn hoàn tất được trên inbox tổng quát).

### 5. Vault thông tin xác thực (credential vault)

HTTP outbound cần secret: tổ chức tạo entry qua
`POST /api/v1/bizapp-credentials` (`format = api_key | bearer | basic`), nền tảng
mã hoá **AES-256-GCM** qua `BIZAPP_ENC_KEY` (sinh bằng `openssl rand -base64 32`,
bắt buộc khi dùng; bật/tắt bằng `BIZAPPS_ENABLED`), chỉ hiện metadata khi đọc, và
`external_call` giải mã just-in-time để gắn header. Không bao giờ log secret.
Kill-switch `BIZAPPS_ENABLED=false` biến toàn bộ bề mặt `/api/v1/bizapp*` thành 404.

### 6. Webhook và audit

Mọi lifecycle đi qua Spring event `BizAppLifecycleEvent` → bridge
`BizAppWebhookBridge` (AFTER_COMMIT) → outbox chung như webhook của workflow:
envelope HMAC-SHA256, retry có backoff, lọc theo `acceptsEventListener`. Kiểu sự
kiện: `bizapp.<kind>.record.created|updated|archived|submitted|decided`.
Audit: `BIZAPP_APP_INSTALLED/UNINSTALLED`, `BIZAPP_TEMPLATE_SAVED/DELETED`,
`BIZAPP_RECORD_CREATED/UPDATED/ARCHIVED/SUBMITTED/DECIDED`,
`BIZAPP_CREDENTIAL_CREATED/DELETED`.

### 7. Kiến trúc module

Module `bizapp` là business module ArchUnit: chỉ giao tiếp với
`workflow`/`notification`/`tenant` qua `*Api` published boundary
(`WorkflowRuntimeApi`, `NotificationRuntimeApi`, `TenantIdentityApi`), chung
`common/` cho event; JDBC chỉ nằm trong `bizapp/query`. Template platform
(tải tĩnh từ classpath) được guard kép: test Java
`BizAppBundledTemplatesTest` + CI job `marketplace-validate`
(`scripts/marketplace_validate.py`).

---

## BizApps — the installable business app platform

A BizApp is a **declarative, installable business application** bound to a
workspace, running on the existing platform services instead of a separate
backend: a workspace-scoped JSONB record store, approvals through the embedded
Flowable engine, transactional email over the org's SMTP channel, HMAC-signed
webhooks, and outbound HTTP with an org-scoped secret vault. Bundled templates
ship: **CRM Lite** (lead pipeline) and **Booking** (appointment scheduling with
a confirmation approval hop). The design follows Flowise's patterns: validated
schema gate first, a two-layer marketplace (platform + org-crafted), and
"node" made concrete as executable blocks.

### 1. The `bizapp.v1` manifest

Every BizApp is one JSON document (`schema: "bizapp.v1"`):

| Section | Meaning |
| --- | --- |
| `kind` | stable app key (`crm`, `booking`) used in URLs and webhook types |
| `entities` | declarative record types: `fields[]` (`string/number/boolean/enum/date`, `required`, `maxLen`, `format: email`), `hooks` |
| `blocks` | executable blocks: `approval_workitem`, `notify`, `webhook_out`, `external_call`, `report`, `registry`, `form`, `timeline` |
| `rbac` | `read/write/delete/approve` → allowed roles (`ORG_OWNER`, `WORKSPACE_ADMIN`, `MEMBER`); missing `write` = admins only |
| `ui` | runtime view order: `registry:<entity>`, `form:<entity>`, `timeline:<entity>`, `report:<view>`, `assistant:<block>` |

**The validator** (`BizAppManifestValidator`) is the single security gate,
enforced on *install*, *template save*, and *upgrade*:

- report SQL must be **exactly one SELECT statement** — no `UNION`,
  `INSERT`/`UPDATE`/`DROP`…, no `${…}` interpolation, and the only allowed
  named binds are `:tenant` / `:app`;
- secret-looking keys (`password`, `secret`, `token`, `apiKey`…) are rejected —
  the only allowed form is a `credentialRef` into the org vault;
- `external_call` requires `credentialRef`, inline secrets never pass;
- `bpmnTemplate` is locked to `processes/<name>.bpmn20.xml` (no `..` traversal);
- hooks may only target a block id or `webhook_out:<event>`; every `ui`
  reference must resolve (entity/report view/block).

### 2. Lifecycle: install → use → uninstall

| Action | Route (`ORG_OWNER` only) |
| --- | --- |
| Browse (platform + org templates) | `GET /api/v1/marketplace/bizapps` |
| Install (`{"templateRef":"platform:crm"}`) | `POST /api/v1/apps/install` |
| Uninstall (soft; records kept) | `DELETE /api/v1/apps/{kind}` |
| Inspect app + frozen manifest | `GET /api/v1/bizapps` · `GET /api/v1/bizapps/{kind}` |
| Replace org-level app config | `PATCH /api/v1/bizapps/{kind}/config` |
| Save a running app as an org template | `POST /api/v1/marketplace/bizapps` |
| Delete an org template | `DELETE /api/v1/marketplace/bizapps/{id}` |

Installation: the validator gates first → the manifest is **frozen** onto
`bizapp_installations` (later template edits never mutate a running app;
upgrades are explicit re-installs) → when the app declares an
`approval_workitem` block, `processes/bizapp-single-approval.bpmn20.xml` is
**deployed through `WorkflowRuntimeApi`** as `bizapp-<kind>` → audit
`BIZAPP_APP_INSTALLED`. Uninstall flips `status=DISABLED`: records and audit
history survive.

### 3. The record store

One shared table, `bizapp_records` (JSONB `payload` + `runtime`), serves every
app, paged per workspace + app + entity:

| Action | Route |
| --- | --- |
| List records | `GET /api/v1/bizapp-data/{kind}/{entity}?includeArchived=&page=&size=` |
| Create / read / patch a record | `POST` / `GET` / `PATCH /api/v1/bizapp-data/{kind}/{entity}[/{recordId}]` |
| Archive a record | `DELETE /api/v1/bizapp-data/{kind}/{entity}/{recordId}` |
| Run a report view | `POST /api/v1/bizapps/{kind}/reports/{viewName}` (admins) |
| Submit a record for approval | `POST /api/v1/bizapp-data/{kind}/{entity}/{recordId}/submit` |

- **Payloads are strictly validated**: unknown fields, missing required fields,
  or malformed `email`/`date`/`enum` values return 400.
- **`runtime`** (never user data) holds `processId`, `approvalStatus`
  (`PENDING → APPROVED | REJECTED`) and the latest decision.
- **RBAC**: every write re-checks the manifest schema plus the workspace role,
  re-resolved per request — the client is never trusted.

### 4. Approvals through the Flowable engine

An app with an `approval_workitem` block gains a "Submit for approval" action:
`submit` starts the deployed process (candidate group from `candidateRole`).
Reviewers claim/complete through the **generic engine inbox**
`/api/v1/workflow/engine/tasks` (`decision = APPROVE|REJECT`, `decidedBy`). At
the process end the `bizAppRecordDecisionDelegate` writes the decision into
`runtime` in the same transaction and raises `bizapp.<kind>.record.decided`
into the webhook outbox after commit (fully separate from the document flow —
BizApp tasks are explicitly *not* flagged as the document approval domain, so
they stay completable on the generic surface).

### 5. The credential vault

Outbound HTTP needing secrets: the organization creates entries via
`POST /api/v1/bizapp-credentials` (`format = api_key | bearer | basic`), stored
**AES-256-GCM**-encrypted under `BIZAPP_ENC_KEY` (generate with
`openssl rand -base64 32`; required when stored secrets are used; feature
kill-switch `BIZAPPS_ENABLED`), listings show metadata only, and
`external_call` decrypts just-in-time to attach a header. Secrets are never
logged. `BIZAPPS_ENABLED=false` turns the whole `/api/v1/bizapp*` surface 404.

### 6. Webhooks and audit

Every lifecycle step goes through the Spring event `BizAppLifecycleEvent` →
`BizAppWebhookBridge` (AFTER_COMMIT) → the same durable outbox as workflow
webhooks: HMAC-SHA256 envelope, retry with backoff, per-endpoint event
filtering (`acceptsEventType`). Event kinds:
`bizapp.<kind>.record.created|updated|archived|submitted|decided`.
Audit actions: `BIZAPP_APP_INSTALLED/UNINSTALLED`,
`BIZAPP_TEMPLATE_SAVED/DELETED`,
`BIZAPP_RECORD_CREATED/UPDATED/ARCHIVED/SUBMITTED/DECIDED`,
`BIZAPP_CREDENTIAL_CREATED/DELETED`.

### 7. Module architecture

`bizapp` is an ArchUnit business module: it talks to
`workflow`/`notification`/`tenant` only through published `*Api` boundaries
(`WorkflowRuntimeApi`, `NotificationRuntimeApi`, `TenantIdentityApi`), shares
`common/` for events, and keeps JDBC inside the `bizapp/query` package.
Bundled platform templates (loaded from the classpath) are double-gated: the
Java test `BizAppBundledTemplatesTest` plus the CI `marketplace-validate` job
(`scripts/marketplace_validate.py`).
