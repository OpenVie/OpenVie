# Integrating with the OpenVie core

[Documentation index](README.md) · [Architecture](ARCHITECTURE.md) · [Deployment](DEPLOYMENT.md) · [DX-OS mapping](DX_OS.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#tích-hợp-với-lõi-openvie--tiếng-việt) · [English](#integrating-with-the-openvie-core-1)

---

## Tích hợp với lõi OpenVie — Tiếng Việt

Lõi OpenVie là headless: mọi năng lực đi qua HTTP JSON tại `/api/v1/**` (và gRPC nội bộ cho tầng AI).
Ứng dụng web ở `frontend/` chỉ là **một** ứng dụng minh họa trên lõi đó. Doanh nghiệp muốn tự tích hợp
có thể self-host toàn bộ backend và gọi thẳng API — hoặc dùng luôn app tham chiếu.

### 1. Điểm vào và định danh

- **Base URL:** đặt một reverse proxy/APISIX trước `api/`; mọi route nghiệp vụ nằm dưới `/api/v1/`.
- **Người dùng (browser):** `POST /api/v1/auth/login` (mật khẩu) hoặc Keycloak OIDC
  (`/api/v1/auth/sso/exchange`, Authorization Code + PKCE) → trả access token JWT (Bearer) + refresh token.
- **Gọi server-to-server:** dùng `Authorization: Bearer <access_token>` và
  `POST /api/v1/auth/refresh` để xoay vòng. Token refresh đọc theo thứ tự: cookie `refresh_token`,
  header `X-Refresh-Token`, body `{"refresh_token": "..."}` — client không có cookie dùng header/body.
- Không có header tenant: workspace, người dùng và vai trò được server suy ra từ token và
  membership đang sống trong DB ở **mỗi** request.

### 2. API key (machine-to-machine)

Chủ sở hữu tổ chức (`ORG_OWNER`) tạo khóa:

```bash
curl -X POST "$BASE/api/v1/admin/api-keys" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"DMS bridge","workspaceId":"<workspace-uuid>","role":"MEMBER"}'
# → 201 {"id":…, "key":"ovk_…", "keyView":{…}}
```

- `key` (`ovk_…`) chỉ xuất hiện **một lần**; server chỉ lưu hash SHA-256.
- Khóa xác thực như một **service member** thật (có user row + membership) nên mọi quy tắc
  phân quyền, audit, và khóa ngoại hiện có áp dụng y nguyên — không cần đường code riêng.
- Dùng: `Authorization: Bearer ovk_…`. Thu hồi: `DELETE /api/v1/admin/api-keys/{keyId}` →
  khóa ngừng xác thực và tài khoản service bị tạm ngưng ngay.
- Danh sách: `GET /api/v1/admin/api-keys` (luôn che secret, chỉ hiện `keyPrefix`).

> Keycloak client-credentials là đường M2M thứ hai ở tầng IdP (confidential client `openvie-backend`);
> nó cấu hình trong Keycloak, không phải một đường code riêng trong API.

### 3. Chat đồng bộ (server-to-server)

Endpoint chat hỗ trợ hai chế độ trên cùng một pipeline (fingerprint, replay idempotent, kiểm tra scope):

```bash
curl -X POST "$BASE/api/v1/chat/sessions/$SESSION_ID/messages" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  -d '{"content":"Tóm tắt hợp đồng này","documentIds":["<doc-uuid>"]}'
```

- `Accept: application/json` → chờ và trả câu trả lời cuối cùng dạng JSON (dành cho tích hợp).
- Mọi `Accept` khác → stream SSE (`content`/`reset`/`complete`/`error`) như UI đang dùng.
- `documentIds` (tối đa 20 tài liệu `COMPLETED` của kho tri thức phiên đó) thu hẹp phạm vi truy hồi;
  bỏ trống = cả kho. Gửi `Idempotency-Key` để replay đúng lượt.

### 4. Webhook (đẩy sự kiện có chữ ký)

```bash
curl -X POST "$BASE/api/v1/admin/webhook-endpoints" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"DMS bridge","url":"https://dms.example.com/openvie/hooks","events":["document.status.changed"]}'
# → 201 {"id":…, "secret":"whsec_…", "endpoint":{…}}   (secret chỉ hiện một lần)
```

- Sự kiện hiện có: `document.status.changed` (tiến độ hấp thụ), `workflow.started`,
  `workflow.approved`, `workflow.rejected`, `workflow.escalated`, và `webhook.test`.
- Body là envelope JSON: `{ "id", "type", "occurred_at", "workspace_id", "data" }`.
  `id` (`X-OpenVie-Event-Id`) ổn định cho một sự kiện → dùng làm khóa chống trùng phía nhận.
- Chữ ký: header `X-OpenVie-Signature: t=<unix>,v1=<hex>` với
  `v1 = HMAC_SHA256(secret, "<t>.<raw_body>")`. Xác minh (và từ chối nếu `t` quá cũ):

```ts
import { createHmac, timingSafeEqual } from "node:crypto";
const [t, v1] = header.split(",").map((p) => p.split("=")[1]);
const expected = createHmac("sha256", secret).update(`${t}.${rawBody}`).digest("hex");
const ok = Math.abs(Date.now() / 1000 - Number(t)) < 300
  && timingSafeEqual(Buffer.from(v1, "hex"), Buffer.from(expected, "hex"));
```

- Giao nhận: ghi outbox trong cùng transaction với thay đổi nghiệp vụ, dispatcher bất đồng bộ retry
  có giới hạn (30 s → 32 m, 5 lần) rồi chuyển `DEAD`. Xem log:
  `GET /api/v1/admin/webhook-endpoints/{id}/deliveries`; gửi thử: `POST …/{id}/test`.
- `DELETE` endpoint ngừng giao ngay (hàng outbox của nó bị xóa theo cascade).

### 5. Workflow engine (BPMN tổng quát)

Ngoài luồng phê duyệt tài liệu (`/api/v1/workflow/processes*`), engine Flowable còn mở API tổng quát
dưới `/api/v1/workflow/engine` (phạm vi workspace qua tenancy của engine):

| Việc | Route |
| --- | --- |
| Triển khai định nghĩa BPMN 2.0 (admin) | `POST /api/v1/workflow/engine/definitions` |
| Liệt kê định nghĩa | `GET /api/v1/workflow/engine/definitions` |
| Khởi động process + biến | `POST /api/v1/workflow/engine/processes` |
| Liệt kê process đang chạy | `GET /api/v1/workflow/engine/processes` |
| Lịch sử một process | `GET /api/v1/workflow/engine/history/processes/{processId}` |
| Inbox tác vụ tổng quát | `GET /api/v1/workflow/engine/tasks` |
| Nhận / hoàn tất tác vụ | `POST /api/v1/workflow/engine/tasks/{taskId}/claim` · `/complete` |

Tác vụ thuộc luồng phê duyệt tài liệu được **từ chối** claim/complete thô (409) để không vượt qua
nhật ký tài liệu.

### 6. Giới hạn tần suất và kill-switch

- Cổng biên (APISIX) giới hạn theo route; trong API, traffic **đã xác thực** được đếm theo
  `tenant:user` trong Redis — `app.rate-limit.consumer-requests-per-minute` (mặc định 600/phút) —
  trả `X-RateLimit-Limit`/`X-RateLimit-Remaining`, và `429` + `Retry-After` khi vượt. Fail-open khi Redis lỗi.
- Traffic public giới hạn riêng (`app.rate-limit.public-requests-per-minute`).
- Kill-switch hành vi: `WEBHOOKS_ENABLED=false` (ngừng giao ra ngoài, outbox vẫn ghi — không mất dữ liệu),
  `WEBHOOKS_DISPATCH_INTERVAL_MS`, `CONSUMER_RATE_LIMIT_PER_MINUTE`.

### 7. Ghi chú self-host

- Cấu hình, secret và profile prod: xem [DEPLOYMENT.md](DEPLOYMENT.md); prod tắt Swagger UI
  (`/v3/api-docs` → 404) — dùng spec ở dev/test để sinh SDK.
- gRPC AI (`:50051`) là bề mặt nội bộ (mTLS); ứng dụng khách chỉ nên gọi HTTP `/api/v1/**`.

### 8. Bộ sưu tập Postman mẫu (Postman Collection)

Bộ sưu tập Postman mẫu đầy đủ kèm các biến môi trường (`baseUrl`, `token`, `workspaceId`, `sessionId`) được lưu trữ sẵn tại:
[`contracts/postman/openvie_integration.postman_collection.json`](../contracts/postman/openvie_integration.postman_collection.json).
Bao gồm đầy đủ các kịch bản mẫu: Xác thực, Cấp phát khóa API máy (M2M), Chat đồng bộ JSON / stream SSE, Đăng ký Webhook, và Thao tác động cơ quy trình BPMN Flowable L2.

---

## Integrating with the OpenVie core

The OpenVie core is headless: every capability is available over HTTP JSON at `/api/v1/**` (plus
internal gRPC for the AI tier). The web app in `frontend/` is only **one** demo application on that
core. An enterprise that wants its own integration can self-host the whole backend and call the API
directly — or reuse the reference app as-is.

### 1. Entry point and identity

- **Base URL:** put a reverse proxy/APISIX in front of `api/`; business routes live under `/api/v1/`.
- **Human (browser):** `POST /api/v1/auth/login` (password) or Keycloak OIDC
  (`/api/v1/auth/sso/exchange`, Authorization Code + PKCE) → JWT access token (Bearer) + refresh token.
- **Server-to-server:** send `Authorization: Bearer <access_token>` and rotate with
  `POST /api/v1/auth/refresh`. The refresh token is read from the `refresh_token` cookie, the
  `X-Refresh-Token` header, or a `{"refresh_token": "..."}` body — in that order — so a cookie-less
  client uses the header or body form.
- There is no tenant header: workspace, user, and role are derived server-side from the token and the
  live membership on **every** request.

### 2. API keys (machine-to-machine)

An organization owner (`ORG_OWNER`) creates a key:

```bash
curl -X POST "$BASE/api/v1/admin/api-keys" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"DMS bridge","workspaceId":"<workspace-uuid>","role":"MEMBER"}'
# → 201 {"id":…, "key":"ovk_…", "keyView":{…}}
```

- `key` (`ovk_…`) is shown **once**; only its SHA-256 hash is stored.
- The key authenticates as a real **service member** (a user row plus a workspace membership), so
  every existing authorization rule, audit record, and foreign key applies unchanged — no separate
  code path.
- Use it as `Authorization: Bearer ovk_…`. Revoke with `DELETE /api/v1/admin/api-keys/{keyId}` —
  the key stops authenticating and its service account is suspended immediately.
- List with `GET /api/v1/admin/api-keys` (secrets are always masked; only `keyPrefix` is shown).

> Keycloak client-credentials is the second M2M path at the IdP tier (confidential client
> `openvie-backend`); it is Keycloak configuration, not a separate API code path.

### 3. Synchronous chat (server-to-server)

The chat endpoint supports two modes on the same pipeline (fingerprint, idempotent replay, scope
validation):

```bash
curl -X POST "$BASE/api/v1/chat/sessions/$SESSION_ID/messages" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  -d '{"content":"Summarize this contract","documentIds":["<doc-uuid>"]}'
```

- `Accept: application/json` → wait and return the final answer as JSON (integration mode).
- Any other `Accept` → stream SSE (`content`/`reset`/`complete`/`error`), exactly as the UI does.
- `documentIds` (at most 20 `COMPLETED` documents of that session's knowledge base) narrows retrieval;
  absent means the whole knowledge base. Send an `Idempotency-Key` to replay the same turn.

### 4. Webhooks (signed event push)

```bash
curl -X POST "$BASE/api/v1/admin/webhook-endpoints" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"DMS bridge","url":"https://dms.example.com/openvie/hooks","events":["document.status.changed"]}'
# → 201 {"id":…, "secret":"whsec_…", "endpoint":{…}}   (secret shown once)
```

- Known events: `document.status.changed` (ingestion progress), `workflow.started`,
  `workflow.approved`, `workflow.rejected`, `workflow.escalated`, and `webhook.test`.
- The body is a JSON envelope: `{ "id", "type", "occurred_at", "workspace_id", "data" }`.
  `id` (`X-OpenVie-Event-Id`) is stable per event → use it as the receiver-side dedupe key.
- Signature: header `X-OpenVie-Signature: t=<unix>,v1=<hex>` where
  `v1 = HMAC_SHA256(secret, "<t>.<raw_body>")`. Verify it (and reject stale `t`):

```ts
import { createHmac, timingSafeEqual } from "node:crypto";
const [t, v1] = header.split(",").map((p) => p.split("=")[1]);
const expected = createHmac("sha256", secret).update(`${t}.${rawBody}`).digest("hex");
const ok = Math.abs(Date.now() / 1000 - Number(t)) < 300
  && timingSafeEqual(Buffer.from(v1, "hex"), Buffer.from(expected, "hex"));
```

- Delivery: the outbox row is written in the same transaction as the domain change; an asynchronous
  dispatcher retries with bounded backoff (30 s → 32 m, 5 attempts) and then moves the row to `DEAD`.
  Inspect with `GET /api/v1/admin/webhook-endpoints/{id}/deliveries`; send a test with
  `POST …/{id}/test`.
- `DELETE` on an endpoint stops delivery immediately (its outbox rows are discarded by cascade).

### 5. Workflow engine (generic BPMN)

Besides the document-approval flow (`/api/v1/workflow/processes*`), the Flowable engine also exposes a
generic API under `/api/v1/workflow/engine` (workspace-scoped through engine tenancy):

| Task | Route |
| --- | --- |
| Deploy a BPMN 2.0 definition (admin) | `POST /api/v1/workflow/engine/definitions` |
| List definitions | `GET /api/v1/workflow/engine/definitions` |
| Start a process with variables | `POST /api/v1/workflow/engine/processes` |
| List running processes | `GET /api/v1/workflow/engine/processes` |
| History of one process | `GET /api/v1/workflow/engine/history/processes/{processId}` |
| Generic task inbox | `GET /api/v1/workflow/engine/tasks` |
| Claim / complete a task | `POST /api/v1/workflow/engine/tasks/{taskId}/claim` · `/complete` |

Tasks belonging to the document-approval flow are **refused** for raw claim/complete (409) so an
integrator cannot bypass the document journal.

### 6. Rate limits and kill-switches

- The edge (APISIX) limits per route; inside the API, **authenticated** traffic is counted per
  `tenant:user` in Redis — `app.rate-limit.consumer-requests-per-minute` (default 600/min) — returning
  `X-RateLimit-Limit`/`X-RateLimit-Remaining` and `429` + `Retry-After` at the ceiling. It fails open
  when Redis is unavailable.
- Public traffic has its own limit (`app.rate-limit.public-requests-per-minute`).
- Behavioral kill-switches: `WEBHOOKS_ENABLED=false` (stop outbound delivery while the outbox keeps
  journaling — no data loss), `WEBHOOKS_DISPATCH_INTERVAL_MS`, `CONSUMER_RATE_LIMIT_PER_MINUTE`.

### 7. Self-host notes

- Configuration, secrets, and the prod profile: see [DEPLOYMENT.md](DEPLOYMENT.md); production disables
  Swagger UI (`/v3/api-docs` → 404) — use the dev/test spec to generate SDKs.
- AI gRPC (`:50051`) is an internal surface (mTLS); client applications should call HTTP `/api/v1/**`.

### 8. Postman Collection

A ready-to-import Postman collection with configured environment variables (`baseUrl`, `token`, `workspaceId`, `sessionId`) is provided at:
[`contracts/postman/openvie_integration.postman_collection.json`](../contracts/postman/openvie_integration.postman_collection.json).
It covers all integration scenarios: Authentication, Machine Credentials (M2M API Keys), Document Chat (Sync JSON & SSE), Signed Webhooks, and Flowable L2 BPMN Workflow Engine operations.
