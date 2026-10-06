# Java API modular-monolith guide

> **Ngôn ngữ / Language:** [Tiếng Việt](#hướng-dẫn-kiến-trúc-modular-monolith-java-api--tiếng-việt) · [English](#java-api-modular-monolith-guide-1)

---

## Hướng dẫn kiến trúc Modular-Monolith Java API — Tiếng Việt

Tài liệu này giải thích cách tổ chức API Spring Boot (`com.cacanode.api`) và quy tắc mở rộng tính năng mà không phá vỡ ranh giới module.

Ứng dụng là một **Modular Monolith**: một kho mã, một JVM, một ứng dụng triển khai, một cơ sở dữ liệu, nhưng chia thành các module nghiệp vụ độc lập với ranh giới hợp đồng chặt chẽ.

### Bốn nguyên tắc bất khả xâm phạm

1. **Giao tiếp liên module chỉ qua `<module>.api` hoặc `<module>.api.event`:** Không bao giờ import controller, service, repository, entity JPA hoặc DTO nội bộ của module khác.
2. **Ranh giới đồng bộ là các Interface tập trung vào năng lực:** Gói `api` sở hữu các Command, Result, DTO, Enum và Exception ranh giới. Không để lộ JPA entity hay servlet type.
3. **Mỗi bảng cơ sở dữ liệu chỉ do một module sở hữu độc quyền:** Không join bảng chéo module trong mã nghiệp vụ. Truy xuất qua API đồng bộ hoặc tiêu thụ sự kiện.
4. **Phản ứng bất đồng bộ độc lập dùng sự kiện bền vững:** Sử dụng outbox sự kiện module bền vững (`module_event_outbox` / `inbox`) với cơ chế retry và idempotent, không dùng `@Async` tùy tiện.

### Sơ đồ phụ thuộc cho phép (Acyclic Graph)

```text
tenant       -> ai.api
auth         -> tenant.api, auth.api.event
chat         -> ai.api, document.api, tenant.api
document     -> ai.api, tenant.api
notification -> auth.api.event, tenant.api.event
common       -> không phụ thuộc module nghiệp vụ nào
bootstrap    -> kết nối (wiring) tất cả các module
```

### Danh mục Module và Quyền sở hữu Bảng

| Module | Trách nhiệm | Ranh giới API đồng bộ | Bảng sở hữu độc quyền |
| --- | --- | --- | --- |
| `ai` | Cấu hình mô hình và giao tiếp gRPC với inference service | `AiInferenceApi`, `ModelConfigurationApi` | `model_config_versions` |
| `auth` | Đăng ký, đăng nhập JWT, đổi workspace, quản lý mật khẩu | Controller REST (gọi `tenant.api`) | `refresh_tokens` |
| `chat` | Hội thoại, tin nhắn, turns, idempotency | Nội bộ chat (tiêu thụ `ai`, `document`, `tenant`) | `chat_sessions`, `chat_messages`, `chat_turns` |
| `document` | Quản lý tài liệu, upload S3, vận chuyển ingestion RabbitMQ | `DocumentApi.validateCitations` | `documents`, `internal_event_outbox`, `internal_event_inbox` |
| `notification` | Thông báo in-app, kênh thông báo, gửi email giao dịch | `DeliveryAvailability` | `notifications`, `notification_channels` |
| `tenant` | Tổ chức, workspace, tài khoản, phân quyền, KB, chatbot | `TenantIdentityApi`, `TenantWorkspaceApi` | `organizations`, `tenants`, `users`, `workspace_members`, `invitations`, `knowledge_bases`, `chatbots` |
| `bootstrap` | Cấu hình bảo mật, kết nối ứng dụng, khởi động | Wiring | Không sở hữu bảng |

### Quy trình sự kiện Module bền vững

- **Bên phát (Producer):** Ghi sự kiện vào `module_event_outbox` trong cùng giao dịch nghiệp vụ. Relay định kỳ quét và phát tán đồng bộ.
- **Bên nhận (Consumer):** Sử dụng `@EventListener`, đánh dấu `@Transactional(propagation = Propagation.REQUIRES_NEW)` và gọi `inboxService.claim("consumer-name")` trước khi xử lý để đảm bảo idempotency.

### Bảng vị trí đặt mã nguồn mới

| Mã nguồn mới | Vị trí chính xác |
| --- | --- |
| Interface gọi đồng bộ liên module | Gói `<module>.api` của module sở hữu |
| Command / Result / Enum ranh giới | Gói `<module>.api` của module sở hữu |
| Sự kiện nghiệp vụ phát tán | Gói `<module>.api.event` của module phát |
| DTO request/response chỉ dùng cho REST | Gói `<module>.dto` nội bộ |
| JPA Entity / Repository | Gói `<module>.model` hoặc `<module>.repository` |
| Hạ tầng dùng chung trung lập | Gói `common` |
| Kết nối cấu hình toàn ứng dụng | Gói `bootstrap` |

### Kiểm chứng kiến trúc

Kiểm tra ranh giới kiến trúc bằng ArchUnit:
```bash
make -C api test
```
`ModularMonolithArchitectureTest` và `TableOwnershipTest` sẽ thất bại nếu có bất kỳ vi phạm nào về ranh giới gói hoặc quyền sở hữu bảng.

---

# Java API modular-monolith guide

This guide explains how the Spring Boot API under `com.cacanode.api` is organized and how to add features without violating module boundaries.

The API is a **Modular Monolith**: one repository, one JVM, one deployable application, and one database, divided into business modules with explicit contracts.

## The four non-negotiable rules

1. **Cross modules only through `<module>.api` or `<module>.api.event`:** Never import another module's controller, service, repository, entity, or internal DTO.
2. **Synchronous boundaries are capability-focused interfaces:** The `api` package owns commands, results, DTOs, enums, and exceptions. Never leak JPA entities or servlet types.
3. **One module exclusively owns each database table:** No cross-module SQL joins at runtime. Access data via synchronous owner APIs or event projections.
4. **Independent reactions use producer-owned durable events:** Use the durable outbox (`module_event_outbox` / `inbox`) for reliable asynchronous events.

## Allowed dependency graph

```text
tenant       -> ai.api
auth         -> tenant.api, auth.api.event
chat         -> ai.api, document.api, tenant.api
document     -> ai.api, tenant.api
notification -> auth.api.event, tenant.api.event
common       -> no business module dependencies
bootstrap    -> wires all modules together
```

## Module catalog and table ownership

| Module | Responsibility | Synchronous boundaries | Owned tables |
| --- | --- | --- | --- |
| `ai` | Model configuration and gRPC inference | `AiInferenceApi`, `ModelConfigurationApi` | `model_config_versions` |
| `auth` | Auth flows, JWTs, workspace switching | REST controllers (calls `tenant.api`) | `refresh_tokens` |
| `chat` | Playground chat, turns, idempotency | Internal orchestration | `chat_sessions`, `chat_messages`, `chat_turns` |
| `document` | Document lifecycle, S3, RabbitMQ ingestion | `DocumentApi.validateCitations` | `documents`, `internal_event_outbox`, `internal_event_inbox` |
| `notification` | Notifications, channels, transactional mail | `DeliveryAvailability` | `notifications`, `notification_channels` |
| `tenant` | Orgs, workspaces, users, memberships, KBs | `TenantIdentityApi`, `TenantWorkspaceApi` | `organizations`, `tenants`, `users`, `workspace_members`, `invitations`, `knowledge_bases`, `chatbots` |
| `bootstrap` | Security, wiring, readiness | None | None |

## Durable module events

- **Producer:** Saves events to `module_event_outbox` within the same business transaction. An outbox relay delivers them synchronously to consumers.
- **Consumer:** Uses synchronous `@EventListener` with `@Transactional(propagation = Propagation.REQUIRES_NEW)` and claims the event in `inboxService.claim("consumer-name")` before execution to guarantee idempotent processing.

## Code placement reference

| Code type | Target location |
| --- | --- |
| Cross-module callable interface | `<module>.api` |
| Boundary command / result / enum | `<module>.api` |
| Published business event | `<module>.api.event` |
| Controller-only DTO | `<module>.dto` |
| JPA Entity / Repository | `<module>.model` or `<module>.repository` |
| Reusable technical infrastructure | `common` |
| Wiring and configuration | `bootstrap` |

## Architecture enforcement

Run ArchUnit tests locally before committing:
```bash
make -C api test
```
`ModularMonolithArchitectureTest` and `TableOwnershipTest` enforce package boundaries and table ownership strictly with zero allowlist exceptions.
