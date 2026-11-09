# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [v1.0.0] — 2026-11-09

Khởi hành DX-OS 3 tầng & nền tảng BizApp hội thoại (xem [`README.md`](README.md#lộ-trình-mvp)); đóng băng hợp đồng `bizapp.v1` (SemVer 2.0)

### Docs
- **Rewrote `docs/BIZAPPS.md` into a full BizApp reference** (bilingual, mirroring sections): the `bizapp.v1` manifest field-by-field (limits, defaults, real compatibility rules such as `maxLen` vs `maxLength`), effective `rbac` semantics (per capability defaults, entity-level grants, submit→write fallback), hook execution order and best-effort guarantees, a complete blocks catalog (`approval_workitem` incl. BPMN fallback and SLA variables, `notify` recipient resolution, `webhook_out` events, `external_call` connector parameter table with security boundaries, `report`/`knowledge_base`/`assistant`), declarative report semantics + compiler invariants (500-row cap, reserved params, JSONB casts), runtime record lifecycle and approval state, a deep visual-composer section (canvas edge semantics — hook/report/schedule/assistant — palette/inspector/report-builder/JSON/list behavior, save-vs-upgrade, template query start), knowledge projection targeting (`config.knowledgeBaseId`) with an honest "known limitations" list (top-K retrieval, connector writes that skip lifecycle events, `document_kind` not yet in the vector index, app chat scoping), a full endpoint reference, configuration table, and a troubleshooting table built from real validation messages. `docs/README.md` links the new reference from both task tables.
- **Expanded the in-app `/documentation` BizApp + composer pages** with new sections driven by the same facts: manifest anatomy/limits and a minimal example, declarative reports with admin fence and export behavior, credential vault formats and resolution, canvas edge semantics, report builder walkthrough, AI drafting (draft-only, repair loop, from-chat), template exchange (envelope/license/hash), and upgrade compatibility. Section metadata (`frontend/src/lib/documentation.ts`, en+vi) and message content updated in both languages.

### Added
- **Schema-bound drafting and a manifest key guard**: `POST /api/v1/bizapps/validate`, install/upgrade, template save and template import now reject keys the `bizapp.v1` contract does not declare (top-level `reports`/`hooks`, field `maxLength`, unknown view/metric/order keys) with a corrective message instead of dropping them silently, and AI drafting forwards the real `contracts/bizapp/bizapp.v1.schema.json` body to the model so Ollama structured output (`format`) constrains generation — the AI service ignores a bare file name, which is why drafts used to be free-form. Registry exports are unchanged.
- **CSV and Excel export for BizApp records and reports**: the entity registry and admin report view gain an Export menu (CSV / Excel). `GET /api/v1/bizapps/{kind}/entities/{entity}/export` writes every active record — entity fields in declaration order with `vi`/`en` labels plus ID/status/timestamps — and `POST /api/v1/bizapps/{kind}/reports/{viewName}/export` writes every column and row the declarative view returns, behind the existing workspace-admin fence. Both share one header/row matrix: CSV is UTF-8 with a BOM so Excel reads Vietnamese, every cell is quoted, `= + - @`-leading strings gain an apostrophe against formula injection, and XLSX (`org.apache.poi:poi-ooxml` 5.5.1, `SXSSF`) keeps numbers numeric and never writes formula cells. Registry exports are capped at 10 000 rows with an explicit `400` above the cap; report exports stay bounded by the view's own `limit`. Report headers are the raw row keys because metric aliases have no manifest label.
- **Per-task AI model routing for BizApp drafting**: the Java API now reads `app.bizapps.ai.intent-model`, `slot-model`, `draft-model` and `draft-fallback-model` (blank = the AI service's `LLM_MODEL_ID`), so low-volume manifest generation can use a stronger Ollama model while chat/RAG and intent/slot extraction stay on the fast 3B default. `CompleteJson` in the AI service now honours the per-request `model_id` instead of ignoring it. The draft repair loop tries the primary model up to three times, then retries once with the fallback and records `model`, `attempts` and `fallbackUsed` in draft meta/audit. `scripts/ai/model_eval.py` + golden cases compare candidate models (intent accuracy, schema validity, approval/email/external-call semantics, latency). The draft normalizer now also auto-hooks unreferenced `approval_workitem`/`notify` blocks and derives an `external_call` polling schedule from phrases like “cứ 5 phút”, so a weak model's structurally valid draft still installs and runs.
- **BizApp connector layer for OT/IoT (HTTP + MQTT, durable jobs, typed sinks)**:
  - `external_call` now enqueues a durable `bizapp_connector_jobs` row inside the record transaction instead of calling the provider inline. Workers claim due jobs with `FOR UPDATE SKIP LOCKED`, retry with exponential backoff (default 3 attempts / `PT10S`, capped at 10 minutes), and audit + meter every attempt (`BIZAPP_EXTERNAL_CALL`, `bizapp_connector_runs_total`, `bizapp_connector_duration`).
  - New manifest params (all optional, `params` stays free-form): `protocol` (`http`/`mqtt`), MQTT `brokerUrl`/`topic`/`qos`/`maxMessages`, `auth` (`header`, `hmac`, `oauth2` client-credentials with an 80%-TTL token cache), `sink` (`field` or deduped `record` append), `schedule` (`every`/`entity`/`select`/`limit`), `timeout`, `retry`, and per-block `egress.allowedHosts` intersected with the installation allowlist.
  - Telemetry appends use a SHA-256 `dedupe_key` with a partial unique index (`V23__bizapp_connectors.sql`), so repeated scheduler polls update one row instead of duplicating it. Credentials gain an optional workspace scope and `oauth2`/`mqtt`/`hmac` formats.
  - `SafeOutboundHttp` accepts per-request connect/read timeouts; Paho MQTT client (`org.eclipse.paho.client.mqttv3`, BSD-3-Clause terms, documented in NOTICE) provides bounded subscribe-per-job reads.
- **Manifest-declared record flow, role-aware chat actions, and safe app upgrades**:
  - `rbac.submit` is now a first-class manifest capability (falls back to `rbac.write`); `rbac.read` defaults to every workspace role, while `write`/`delete`/`approve` default to admins. Record get/list/create/update/archive/submit are all fenced by the caller's effective role (`ORG_OWNER` for owners, workspace role otherwise) instead of a coarse admin flag.
  - `approval_workitem` hooks declared on `onCreate`/`onUpdate` now auto-submit the record into the app's deployed Flowable definition. The validator enforces exactly one approval block, hookable hook targets, `candidateRole` inside `rbac.approve`, and `notify` recipients (`creator`, `record.<stringField>`, `config.<key>`, explicit email). `onDecide` hooks run best-effort after the decision; `notify`/`webhook_out` failures are audited without rolling the record write back, while approval failures still do; `external_call` only rolls the write back when its durable job cannot be enqueued.
  - Approval tasks are candidate-scoped: the engine task inbox only lists tasks the caller's role is a candidate for (organization owners see the whole workspace), members must be candidates to claim, and only the current assignee may complete.
  - The chat intent router receives the caller's manifest role, gets a per-app allowed-action menu, hides apps the role cannot read, and answers disallowed actions with a localized role-restriction reply instead of a dead card. Update/archive/submit cards resolve the target `recordId` from the message or a previous record action card and ask which record to use instead of guessing; denied executions persist as `DENIED`.
  - Installed apps can be upgraded in place (`POST /api/v1/apps/{kind}/upgrade`, owner-only): same kind, higher version, same storage, no entity/field removal or type change, records preserved and the approval definition redeployed. The composer and admin catalog upgrade automatically when the kind is already active.
- **Rename an app directly in the Visual Composer**: a pencil action beside the manifest title opens a bilingual (VI/EN) rename dialog. Only the display name changes; the app `kind`, schema, records and workflow stay untouched. The new name is carried by the existing draft-save/install flow, so no separate API call is needed.
- **In-app notification suite & notification bell UI**:
  - New `notifications` schema features (Flyway migration `V21__notifications_read_and_booking_reminders.sql`): `read_at` tracking, optional deep `link`, expanded `notifications_type_check` supporting `BOOKING_REMINDER`, and index optimizations (`idx_notification_user_unread`, `idx_notification_tenant_user`).
  - Full REST controller `/api/v1/notifications`: paged listing `GET /`, unread counter `GET /unread-count`, individual mark-read `POST /{id}/read`, and bulk mark-read `POST /read-all`.
  - Frontend `NotificationBell` component integrated in `AppShell` topbar: unread counter badge, liquid-glass popover dropdown, mark-read interaction, deep linking to target entity, and auto-refresh polling (30 s).
- **24-hour appointment reminder scheduler (UI & email)**:
  - `BizAppBookingReminderScheduler` sweeps active bookings periodically and fires reminders when appointment falls within 24 hours of current time (handling both date-only `yyyy-MM-dd` and datetime `yyyy-MM-ddTHH:mm`).
  - Emits an in-app `BOOKING_REMINDER` notification to the booking creator with deep link to `/apps/booking`.
  - Sends reminder transactional emails via the organization's configured mail transport (SMTP / Mailpit) to both `customerEmail` and the creator.
  - Atomic deduplication: `runtime.reminderSentAt` timestamp prevents duplicate sends; updating the appointment date resets reminder tracking.
  - Extended `NotifyBlock` and bundled `booking.json` to support `record.<field>` recipient resolution (e.g. `record.customerEmail`).

- **Conversational app creation suggestions for natural management inquiries**:
  - `BizAppIntentRouter` recognizes natural business management inquiries (e.g. "Bạn có thể có cách nào quản lý thiết bị IT cho tôi không?", "Làm sao để theo dõi tài sản công ty?") as business capability requests instead of routing them to document RAG.
  - The assistant explains that no app is currently installed, proposes relevant entities and fields (e.g. device ID, name, assigned user, status), and emits an interactive `bizapp.draft` creation card.
  - Distinguishes software management inquiries from formal document procedures and policy questions (SOPs).

### Fixed
- **Chat JSON integration mode answers instead of erroring**: `POST /api/v1/chat/sessions/{id}/messages` with `Accept: application/json` (the server-to-server mode API keys target) failed at handler-mapping time with `HttpMediaTypeNotAcceptableException` surfaced as a generic `500`, because the mapping declared `produces = text/event-stream` and rejected JSON `Accept` s before the in-method JSON branch could ever run. The mapping no longer restricts produces, the JSON branch executes, and regression tests prove both routing (`jsonAcceptRoutesThroughTheHandlerMappingIntoTheJsonAnswer`) and wildcard-`Accept` streaming (`wildcardAcceptStillStreamsEventStream`).
- **API-key usage tracking actually records usage**: `ApiKeyService.authenticate` stamped `last_used_at` inside its own `@Transactional(readOnly = true)` transaction, so the write never flushed and the "Last used" column always stayed empty. Usage stamping now lives in `ApiKeyUsageStamper` (`REQUIRES_NEW`, one throttled `UPDATE … WHERE last_used_at IS NULL OR <= :threshold` statement stamped through the repository), verified against Postgres: first request stamps, bursts inside the 30 s window do not rewrite, and the stamp renews after the window.
- **Creating a chat session no longer requires discovering internal uuids**: `chatbot_id`/`knowledge_base_id` on `POST /api/v1/chat/sessions` are now optional at the boundary — an empty body binds the session to the workspace's default assistant (oldest active chatbot, exactly what `GET /tenants/me/workspace` exposes), while explicit full pairs and a knowledge base alone stay validated (`ChatSessionDefaultsIntegrationTest`). The response still carries the resolved ids, and the persistence layer never writes null ids. Integration docs updated accordingly (`docs/INTEGRATION.md`).
- **Sidebar marks new BizApp surfaces as beta**: the `Apps` and `Marketplace` navigation entries render a small `beta` pill beside the label in both themes, hidden when the sidebar is collapsed (`badge` field on the navigation item), matching the pre-GA maturity of those features.
- **Aggregation questions in chat no longer ask for a record**: the intent classifier reads "PM2.5 trung bình từng trạm là bao nhiêu?" as `APP_RECORD_UPDATE`, so the router replied "I need the specific record" and the question never reached RAG. Record-action intents are now dropped when the turn is a data question (`bao nhiêu`, `trung bình`, `tổng`, `cao nhất`, `how many`, `average`, …) and carries no create/update/archive/submit verb, so the turn falls through to the knowledge digest. Verified live: the same question now answers "PM2.5 trung bình từng trạm là 35.2" citing `bizapp__aq-mqtt-demo__digest.md` (`Report: by_device`).
- **BizApp knowledge projection now actually writes**: the projection listener used the tenant id as the `documents` actor, but `documents.uploaded_by` references `users(id)`, so every ingest failed on the foreign key and records were parked in the FAILED knowledge state — no BizApp record or report digest ever reached the knowledge base, which is why chat could not answer questions about app data. The actor now comes from the event (`created_by`/`updated_by`/`decided_by`/…) with the installer as fallback; verified on a live MQTT app: the device record projects (PROJECTED) and `bizapp__<kind>__digest.md` is ingested (COMPLETED) with the `by_device` aggregate (count/avg/max) and the latest readings.
- **HTTP connector `GET` no longer turns into `POST`**: `HttpConnectorAdapter` always rendered a body, and `HttpURLConnection` silently upgrades GET+body to POST — GET-only APIs (air-quality, weather, health feeds) answered `400` while the manifest said GET. GET/HEAD now send no body and no `Content-Type`; `bodyTemplate` applies to POST/PUT/PATCH. Proven against the local mock gateway (`GET /readings` in its log) and `open-meteo`-style endpoints.
- **Composer edge inspector knows what it is editing**: edges now carry `data.kind` (`hook`/`report`/`ref`/`assistant`). Report-view edges (block → entity) are aggregated into one edge per entity labelled with the view count, because parallel edges on the same path stacked their labels; the inspector lists each view with its own **Remove** action that deletes that view from the report block. Timer-driven `external_call` blocks now draw a dashed, animated, read-only schedule edge (`block → entity`, labelled `every PT30S`), so a connector polled by `params.schedule` is no longer an orphan node; clicking it explains where to edit the interval. Relabelling a derived edge can never invent a hook.
- **Installed apps without hand-authored triggers are reachable, and member write grants are honored**: the router derives conservative two-word aliases from app/entity names (for example “mua sắm” from “Yêu cầu Mua sắm”) so natural requests like “viết một phiếu xin mua sắm” open the installed app instead of proposing a duplicate. AI manifests that put an explicit write list under `entities[].rbac.write` now grant that capability even when the app-level list is admin-only; delete/approve remain app-level so permissions do not widen silently. Draft generation also applies a deterministic rule: when the request says employees/members fill or create records, `MEMBER` is added to app-level read/write (and submit when an approval block exists), while explicit admin-only/denied clauses suppress the grant.
- **Record-detail follow-ups use conversation state instead of more regex**: when an app record needs fields, the assistant now lists the manifest fields immediately and stores `appKind`/`entity` in the session's `bizappPending` state. The next turn (“Cho tôi biết những thông tin cần điền nhé”) returns the field list directly without another intent regex or LLM call; providing fields clears the state.
- **Approval-role changes apply to in-flight work**: upgrading a BizApp now moves unclaimed approval tasks from the old candidate group to the new role and updates the process variable, so workspace-admin changes do not leave requests stranded in the owner queue. Upgrades also auto-advance stale manifest versions instead of rejecting with “version must be greater”, and the composer shows the active workspace beside the install target.
- **Composer install applies current edits and upgrades existing apps**: the draft composer now installs the manifest currently on the canvas (not the last saved copy), detects an active installation and upgrades it with a strictly higher manifest version, then saves that version back to the draft. Previously the draft page could only hit “already installed”, leaving the editor showing a role change that the running app never received.
- **BizApp notification emails now render templates and fail visibly**: notify block `subject`/`title` support `{{record.<field>}}` placeholders, zero-delivery results say why in the hook audit/log, and stored SMTP host/from/username values are trimmed so a trailing space cannot silently break delivery. The local dev channel was corrected to `localhost` for Mailpit.
- **Composer blocks are configured in plain language**: Report now has a builder for tables and grouped summaries (entity, columns, filters, sort, limit, title) and automatically adds `report:<view>` navigation tabs; Webhook Out shows a friendly event checklist and publishes only selected events; Knowledge Base is presented as automatic instead of an empty technical block. Internal block IDs/types moved under Advanced, and canvas/list cards show a readable summary instead of raw parameter JSON.
- **Webhook events now honor the selected list**: `record.created`, `record.updated`, `record.archived`, `record.submitted`, and `record.decided` are published only when the Webhook block selects them; archiving now emits its lifecycle event.
- **Approval processes are chosen from a preset library**: the Composer offers “Single-level approval” and “Two-level approval” (first-level approver then organization owner) instead of exposing a raw `bpmnTemplate` path. Candidate-group authorization now reads the task’s actual group, so first-level `MEMBER` approvals are claimable instead of being locked to workspace admins.
- **Every Composer block now has editable settings**: notify recipients render as labeled chips with a compact add control and suggestions for “Record creator” or an app email field; every block renders its remaining parameters as labeled nested fields (lists, toggles, and grouped objects) instead of raw JSON.
- **Composer Inspector field editor no longer overflows**: field rows shrink the name input with `min-w-0`, keep type/delete controls fixed, and use the Inspector panel’s single vertical scroll instead of a nested scrolling box.
- **Composer nodes show their full contract**: entity nodes list every field and block nodes list every parameter without an internal scrollbar or “+N more”. Auto-layout estimates the taller nodes so they do not overlap after arranging.
- **Composer hook edges are now real graph edits**: connecting an entity to a block creates an `onCreate` hook, clicking an edge lets the user choose `onCreate`/`onUpdate`/`onDecide` or delete it, and the graph → manifest conversion persists hook edges instead of leaving them visual-only. Edges remain entity→block only; duplicate connections are ignored.
- **Approval inbox shows BizApp record context**: task/process views are enriched from the installed manifest and record payload (title, summary, field labels/values), so approvers see what they are approving without opening the app. Old BizApp tasks without display variables are enriched at read time.
- **BizApp approvals complete through the generic inbox**: approve/reject now writes `decision`/`decidedBy` into the process so the BizApp terminal delegate updates the record and runs `onDecide` hooks; document-only write-back is skipped when the task has no `documentId`.
- **BizApp approval tasks appear in the engine inbox**: approval processes now carry the workspace process variable, and the inbox also scopes by the Flowable process tenant so tasks created before that variable existed (including the current pending request) become visible. Claim/process-log checks accept either scope.
- **AI drafts that ask for email on create send it immediately**: when the prompt says to notify on creation, a non-decision `notify` hook is moved from `onDecide` to `onCreate`; approval-result notifications remain on `onDecide`.
- **Draft action no longer reports failure while the API is still generating**: Next’s dev proxy defaulted to a 30s timeout, but an AI draft repair loop can take longer; the UI showed “Thực thi thất bại” while the API later committed the draft. The proxy timeout is now 300s, and bare notify recipients emitted by the model (`manager_email`) are rewritten to validator-valid record references (`record.manager_email`), so the manager-email draft validates on the first attempt instead of repeating the repair loop.
- **Chat no longer mistakes “hộp thư” for Booking’s “họp” slot value, or turns “tạo ứng dụng…” into an install prompt**: app-kind aliases no longer include field option values, and alias matching now requires token boundaries. Explicit build-an-app requests short-circuit to a `CREATE_APP` draft card before app selection, so a catalog app can no longer hijack them.
- **Plain-language app requests no longer dead-end, and AI drafts wire approval automatically**: a conversational request that the intent classifier reads as a record action but no installed app can serve now offers a new app draft instead of returning to RAG (Vietnamese “điền” counts as a write action for the deterministic gate). AI-generated manifests that declare an `approval_workitem` block without any lifecycle hook get that block attached to the first entity’s `onCreate`, so member-created records are actually submitted for approval.
- **Sidebar no longer double-highlights nested admin destinations**: the active item is resolved by longest matching href, so `/admin/organization/api-keys` highlights only “API key” instead of also lighting up its parent “Tổ chức” (and the sliding indicator no longer lands on the wrong entry).
- **Members can create app records from chat when the manifest grants write**: chat action execution no longer hard-requires `ORG_OWNER` for `bizapp.record.*`. App lifecycle actions (install/uninstall/draft) stay owner-only, while record actions are delegated to the app manifest's RBAC using the caller's effective workspace role. The leave app's manifest now grants `MEMBER` write (app-level and entity-level), so members can create their own records and hand them to `WORKSPACE_ADMIN` for approval.
- **“What information do I need?” follow-ups stay inside the app**: an active app context is now recovered from the localized app name (`nameVi`) in the previous turn, and a field-information follow-up lists the entity’s declared fields from the manifest instead of falling into document RAG. No extra LLM call is used for the field list.
- **App clarification prompts now match the conversation context**: the deterministic “provide record details” reply no longer interpolates the manifest entity label (which can be a stale template label such as `Conversation Turns`) or the English app name. It uses the app’s locale-appropriate display name and a neutral “new item/entry” phrase, so a Vietnamese request in an app named `Nghỉ phép` no longer answers “tạo lần trò chuyện gần đây trong On leave”. `InstalledAppSummary` now carries `nameVi` for localized routing and replies.
- **Renaming a draft now updates the installed app card**: saving a draft propagates its Vietnamese/English display name to every ACTIVE installation of the same kind across the organization. The installation's frozen schema, records and workflow stay untouched; only the manifest `name` changes. Existing installations were backfilled by re-saving the renamed draft.
- **Visual Composer toolbar rebalanced**: the manifest title/name area now owns the flexible left column so long names and the schema/kind line no longer collapse against the nav edge; the view switcher stays centered and secondary action labels collapse to icon-only controls before they can overlap. The React Flow watermark is removed from the canvas.
- **App intent routing is manifest-driven instead of domain-hardcoded**: the router no longer names specific apps or business domains in its classifier prompt, regexes or extraction rules. Installed apps are selected by meaning against their own manifest fields (name, description, triggers, entities, fields) through a structured-output selector; classifier few-shot examples and field-role detection (name/email/phone/date/time/notes) are generated from the live manifests. When an operational request cannot be covered by any installed app, the router offers a generic app-draft action carrying the user's own message as the generation hint, instead of falling into document RAG or guessing a domain.
- **Chat composer no longer demands a document when your organization runs apps**: the playground now treats an active installed BizApp anywhere in the organization as a valid conversation surface, so a workspace with zero indexed documents can still send messages. Sending stays blocked only when the organization has neither a completed document in the workspace nor an active app, and the empty-state copy now mentions installing an app as the alternative.
- **AI-drafted approval apps no longer reference unavailable BPMN files**: generated approval blocks are canonicalized to `processes/bizapp-single-approval.bpmn20.xml`; Flyway `V22__normalize_ai_draft_approval_templates.sql` repairs existing AI drafts, and installation retains a generic-process fallback for legacy manifests.
- **BizApp record actions no longer carry values the model invented**: the LLM slot agent (`BizAppSlotAgent`) now keeps a free-text slot (name, email, notes...) only when its value occurs in the user's own messages (case-, diacritic- and whitespace-insensitive); otherwise the slot is dropped and the router asks for it. The extraction prompt now contains user turns only, so the assistant's earlier cards and replies can no longer feed a wrong value back into the next turn. Previously a request naming "Nguyễn Văn B" with no email could produce a confirmable card for "Nguyễn Văn A" / `a@example.com`.
- **Forms of address no longer end up in the stored name**: both the deterministic extractor (`BizAppIntentRouter.cleanNameValue`) and the LLM slot agent drop a leading lowercase "anh/chị/em/ông/bà/cô/chú/bác" (and Latin "Mr/Mrs/Ms/Miss") in name slots, so "anh Trấn Thành" is stored as "Trấn Thành". A capitalized given name such as "Anh Thư" is kept, and the extraction prompt tells the model to omit forms of address.
- **Email templates and logo aligned with brand theme (standalone backend)**:
  - Bundled the modern OpenVie raster logo (`#1fb6ff` Sky Blue with white emblem) directly inside the backend at `api/src/main/resources/static/logo.png`, so email delivery requires no web frontend.
  - `SmtpEmailProvider` embeds the logo directly into MIME messages as an inline `cid:openvie-logo` attachment (RFC 2392), enabling immediate logo display in Mailpit, Gmail, and Outlook even in offline or air-gapped environments.
  - `EmailService.renderShell` updated brand accent from legacy lime (`#c8f53c`) to platform primary (`#1fb6ff`), and styled all typography, tables, and lists to match the dark theme.
  - `NotifyBlock` and reminder emails now format record payloads as styled key-value table cards with human-readable labels instead of raw camelCase bullet lists.
  - Spring Security permits public access to `/logo.png` directly from the backend.

- **Notification bell darkmode contrast and Vietnamese localization**:
  - Resolved portal variable isolation where Tailwind `--foreground` defaulted to near-black `:root` tokens over dark glass surfaces. Applied explicit high-contrast foreground/background styles (`#f2f5f0` on `#12161a` in dark mode, `#10150f` on `#ffffff` in light mode).
  - Added `localizeNotification` helper dynamically translating both historical database notifications and new incoming events (e.g. "Document approved" -> "Tài liệu đã được phê duyệt", "was approved at approval level 1" -> "đã được phê duyệt ở cấp phê duyệt 1", "You're invited to..." -> "Lời mời tham gia...").
  - Replaced harsh Tailwind `divide-y` borders with padded, rounded item cards and subtle section separators matching the app's visual language.
  - Added localized category badge chips for each notification type (`Phê duyệt`, `Nhắc lịch`, `Lời mời`, `Hoàn tất`, `Thất bại`).

- **BizApp action card footer button alignment**:
  - Eliminated conflicting `gap-4` spacing between card sections and removed lopsided `pt-0` padding in `BizAppActionCard` footer.
  - Replaced asymmetric padding with balanced `px-4 py-3`, ensuring action buttons (such as "Cài đặt vào Workspace", "Xác nhận tạo") are vertically centered and seamlessly aligned within the card's bordered footer.
  - Suppressed empty `CardContent` rendering when cards have no description or field payload, preventing awkward whitespace gaps.

- **Chat action execution now supports bizapp.draft**:
  - Added `bizapp.draft` to `ChatActionService.SUPPORTED_ACTION_KINDS` and wired execution to `BizAppRuntimeApi.createDraftFromSession`, fixing the `Unsupported executable action kind: bizapp.draft` conflict error.
  - Generates the `bizapp.v1` manifest draft directly from the active chat session turns, marks the card as `EXECUTED`, and renders a direct link to Visual Composer (`/admin/apps/compose/{draftId}`).
  - Enabled AI manifest drafting by default (`BIZAPPS_AI_DRAFTING_ENABLED=true` in application.yaml, BizAppProperties, and env files), resolving the `AI manifest drafting is disabled on this installation` error when creating drafts from chat.
  - Fixed action card title and button label for draft cards (displays "Tạo ứng dụng" / "Create Application" instead of mislabeled "Cài đặt vào Workspace").
  - Added immediate spinner feedback (`setStatus("EXECUTING")`) and automatic redirection to Visual Composer (`/admin/apps/compose/{draftId}`) upon successful draft creation in `BizAppActionCard`.
  - Allowed retrying action cards in `FAILED` state in `ChatActionService`, preventing actions from becoming permanently stuck after transient errors.
  - Added `normalizeGeneratedManifest` and `@JsonCreator` on `Localized` in `BizAppDraftService` and `BizAppManifest`, unwrapping root `manifest` keys and coercing string labels/names so AI-generated drafts always validate cleanly.
  - Filtered out API key machine service accounts (`@keys.openvie.invalid`) from workspace member directories (`/users`) and organization user lists (`/admin/organization`), keeping human employee rosters clean while preserving backend machine-to-machine authentication.
  - Enhanced `normalizeGeneratedManifest` in `BizAppDraftService` to automatically sanitize multi-entity schema anomalies (`values` -> `options` for enums, `reference` -> `entity` for refs, inverted RBAC maps, and auto-generated default UI views), ensuring complex multi-table drafts validate without errors.
  - Added explicit contract rules for `blocks` (approval workitems, notifications) and entity `hooks` (`onCreate`, `onUpdate`, `onDecide`) in `BizAppDraftService` system prompt, allowing AI generators to automatically connect workflow edges on the visual composer canvas.

## [1.0.0] - 2026-11-04

### Added
- **Conversational BizApp Platform & Smart Assistant Integration**:
  - **Three-Tier Architecture**: Clean architectural isolation separating Tier A (deterministic catalog inspection), Tier B (structured LLM intent classification), and Tier C (unmodified document RAG).
  - **Generic BizApp Data Interaction (W7)**:
    - **Schema-Driven Data Operations**: LLM can now inspect, create, update, archive, and submit records for *any* application in the workspace (bundled or org-custom) driven entirely by the `bizapp.v1` manifest.
    - **Dynamic Schema Digest & PII Shield (W7.1)**: `BizAppSchemaDigest` exposes strictly allow-listed manifest fields to the LLM context while automatically stripping `pii: true` fields (Invariant I-14).
    - **Server-Authoritative Record Actions (W7.4, W7.5)**: Interactive Action Cards (`bizapp.record.create`, `bizapp.record.update`, `bizapp.record.archive`, `bizapp.record.submit`) with SHA-256 `payloadHash` tamper protection, dry-run validation (`INVALID` status badge on missing required fields), and manifest RBAC enforcement.
    - **Entity-Scoped App Data Retrieval (W7.6)**: Projector tags record documents with `source_ref = "bizapp:<kind>:<entity>"`, allowing query scope narrowing to specific entities with safe fallback to app-level scope.
    - **Zero-Latency RAG Neutrality & CI Gates G-11 to G-14**: Full elimination of hardcoded platform app kind literals across the chat module (G-11), closed dispatcher verification (G-12), architectural layer isolation (G-13), and procedure question protection ensuring 100% invariance for regular document RAG.
  - **Interactive Action Cards (W2.1, W5.1)**: Support for interactive `bizapp.install`, `bizapp.uninstall`, `bizapp.catalog`, `bizapp.suggest`, and `bizapp.draft` cards delivered through `chat_messages.action` and `AssistantMessageResponse.action`.
  - **Server-Authoritative Action Execution (W2.2)**: `POST /api/v1/chat/actions/execute` endpoint guarded by `ORG_OWNER` authority; execution parameters read from DB by `messageId` and `actionId` to prevent prompt injection; replay-safe idempotency.
  - **Drafting BizApps from Chat History (W3)**: `POST /api/v1/bizapps/drafts/from-chat` generates valid `bizapp.v1` manifest drafts from conversation context via `CompleteJson` without auto-installation.
  - **Deterministic Answer Composer (W1.2)**: `CatalogAnswerComposer` renders localized, citation-free markdown responses for `LIST_APPS` and `APP_DETAIL` directly from database manifests without calling LLM.
  - **Document Provenance & Safe Retrieval Access Rules (W0.1, W0.5, W4.5)**: Flyway `V18__document_provenance.sql` introduces `document_kind` (`USER`, `BIZAPP_RECORD`, `BIZAPP_DIGEST`) and `source_ref`. System documents are hidden by default from UI (`include_system=false`), undeletable by any user role (403), and excluded from regular document RAG citations.
  - **Wire Protocol Exclusions (W0.3)**: Extended `GenerateAnswerRequest` in `proto/openvie_ai_v1.proto` with additive field `repeated string excluded_document_kinds = 15;`. Filtering applied across Qdrant (`must_not`) and Kuzu Cypher queries.
  - **Graph Retrieval Control (W0.6)**: Added `GRAPH_RETRIEVAL_ENABLED` toggle (defaults to `true`); when disabled, skips HTTP queries to graph-service entirely and feeds empty graph results to fusion.
  - **Debounced Knowledge Projection & Revision Churn Protection (W4)**: Debounced digest refresh to 30 seconds (`app.bizapps.projection-debounce-ms`), enforced flat non-colliding names (`bizapp__<kind>__...`), and eliminated revision bumps on app install/uninstall.
  - **Operational Backfill Command (W0.7)**: Added `ProvenanceBackfillCommand` (`--app.command.mode=bizapp-provenance-backfill`) for normalizing legacy projected documents with `--dry-run` safety.
  - **CI Anti-Regression Gates G-1 to G-10**: Verified prompt invariance (G-3), wire neutrality (G-4), Qdrant backward compatibility (G-5), ArchUnit module boundaries (G-2, G-6), and 15-row matrix coverage (G-10).
- **Full DX-OS 3-Tier Completion & BizApp Platform GA (Tier 3)**:
  - **Frozen `bizapp.v1` Canonical Schema**: Replaced unreleased draft report SQL with declarative, parameter-bound report views (`groupBy`, `metrics`, `filters`, `orderBy`, `limit`, `engine`). Eliminated raw SQL injection and cross-tenant leakage vectors.
  - **Hybrid Storage Architecture (Workstream T)**: Implemented `BizAppStorageRouter` providing clean storage routing between dedicated typed tables (`bizapp_crm_leads`, `bizapp_booking_appointments` via Flyway V16/V17) for platform flagship templates and the generic JSONB store (`bizapp_records`) for org-custom applications.
  - **Knowledge Projection (Workstream D)**: Projected business records and aggregate data digest documents (`bizapp/<kind>/_digest.md`) into the workspace knowledge base, allowing the AI assistant to answer CRM and booking questions with verified source citations. Enforced fail-closed access control requiring `rbac.read` to cover all workspace roles, with strict exclusion of PII and credentials.
  - **Low-Code Visual Composer (Workstream B)**: Built an intuitive visual authoring canvas at `/admin/apps/compose` using `@xyflow/react` and `@dagrejs/dagre` automated graph layout, featuring custom entity and block nodes, component palette, property inspector with vault integration, and an accessible keyboard-first list view.
  - **AI Manifest Drafting (Workstream A)**: Introduced `POST /api/v1/bizapps/drafts/generate` powered by a dedicated `CompleteJson` gRPC RPC in `proto/openvie_ai_v1.proto`, prompt isolation, and automated self-repair loop. Drafts are safely persisted in `bizapp_drafts` (Flyway V14) without auto-installation.
  - **Template Exchange & Provenance (Workstream F)**: Standardized on the `openvie.template.v1` envelope for bundled, custom, and imported templates. Supports envelope export, safe import, remixing with retained upstream attribution, and remote catalog index discovery.
  - **SSRF-Hardened Outbound HTTP (Workstream S)**: Built `SafeOutboundHttp` enforcing HTTPS, installation `allowedHosts` allow-listing, pre-connection DNS SSRF checks rejecting loopback, private RFC 1918, link-local, and cloud metadata addresses (169.254.169.254), disabled redirects, and 256 KiB response caps.
  - **e-Signature Delegation Recipe (Workstream E)**: Added bundled `signature-request.json` recipe template utilizing `external_call` with idempotency headers (`X-Idempotency-Key`) and response mapping (`responseMap`) without key custody or legal liability.
  - **BPMN Approval SLA Timers (Workstream K)**: Extended `bizapp-single-approval.bpmn20.xml` with reminder (PT24H) and escalation (PT48H) boundary timer events.
  - **Lakehouse CDC for BizApp Records (Workstream K)**: Integrated `bizapp_records` into Lakehouse CDC streaming via RabbitMQ and DuckDB OLAP aggregate analytics.
  - **Apache-2.0 & Supply-Chain Compliance (Workstream L)**: Extended license header coverage across all migrations, BPMN XML, workflows, Dockerfiles, and scripts; added automated dependency license check scripts and CI gates (`dependency-licenses`, `artifact-license`); verified `NOTICE` accuracy and packaged `LICENSE` and `NOTICE` inside all distributable artifacts.
  - **Unified 1.0.0 Versioning**: Aligned API, frontend, and Python microservices to `1.0.0`.
## [0.3.2] - 2026-10-24

### Added
- **Data Lineage DAG & Automated Data Quality Framework (DX-OS Principle iii, issue #54)**:
  - **End-to-end Data Lineage DAG**: Added `DocumentLineageGraph`, `LineageNode`, and `LineageEdge` models and `/api/v1/analytics/lakehouse/lineage/documents/{documentId}` endpoint tracing full provenance across the platform: raw document upload (SHA-256 hash, metadata) → parser worker chunking → vector index (Qdrant) & knowledge graph (Kuzu) → RabbitMQ CDC streaming → SeaweedFS S3 Snappy Parquet Lakehouse → DuckDB OLAP queries and RAG evidence anchoring.
  - **Automated Data Quality Framework**: Implemented `evaluate_data_quality` in `DuckDbLakehouseEngine` executing 5 automated DuckDB assertions over Lakehouse datasets: chat completeness and schema, document provenance and chunk integrity, audit trail uniqueness (duplicate detection), workflow decision validity, and temporal chronology. Exposes overall quality score (0–100%) and assertion details via `GET /api/v1/analytics/lakehouse/quality`.
  - **Grafana DuckDB Lakehouse Visualization**: Provisioned `monitoring/grafana/provisioning/datasources/lakehouse.yml` using `yesoreyeram-infinity-datasource` and configured Grafana container to automatically install the Infinity plugin, enabling native visualization of DuckDB analytics, storage KPIs, and data quality metrics without locking operational stores.
- **Edge Malware Quarantine with ClamAV, WAF & Automated Self-Recovery (DX-OS Principle iv, issue #54)**:
  - **Edge Antivirus Quarantine (ClamAV)**: Built `AntivirusScanner` service in Java control plane scanning uploaded document byte streams via ClamAV daemon (`zINSTREAM` protocol on port 3310) with in-memory detection for standard EICAR test signatures. Automatically rejects infected files with HTTP 422 (`MALWARE_DETECTED`), prevents persistence or ingestion queue dispatch, and journals `MALWARE_QUARANTINED` in the immutable audit log.
  - **APISIX Edge Gateway WAF Hardening**: Added `uri-blocker` plugin blocking common exploit scans (`.env`, `.git`, `.svn`, `phpinfo`, etc.) with HTTP 403, and configured strict Zero-Trust security response headers (`X-Content-Type-Options: nosniff`, `X-Frame-Options: SAMEORIGIN`, `X-XSS-Protection`, `Strict-Transport-Security`, `Referrer-Policy`).
  - **Container Self-Healing**: Added `willfarrell/autoheal:1.2.0` daemon service in Docker Compose configurations (`docker-compose.prod.yml`, `docker-compose.yml`) automatically monitoring healthchecks and restarting unresponsive/unhealthy services within < 5 seconds.
  - **Declarative GitOps & Disaster Recovery Tooling**: Created `scripts/ops/gitops_sync.sh` for automated Git reconciliation and rolling infrastructure deployment; added `scripts/ops/disaster_recovery_drill.sh` for non-destructive automated verification drills validating SHA-256 backup manifests and schema parity against isolated scratch databases.
- **Audio & Video Ingestion with Speech-to-Text (ASR) & Media RAG Citations (issue #54)**:
  - **Multi-format media ingestion**: Extended document vault to ingest `DocumentType.AUDIO` (`.mp3`, `.wav`, `.m4a`, `.aac`, `.flac`, `.ogg`, `.wma`) and `DocumentType.VIDEO` (`.mp4`, `.mov`, `.mkv`, `.avi`, `.webm`) alongside text documents, guarded by configurable flags (`ASR_UPLOADS_ENABLED`, `VIDEO_UPLOADS_ENABLED`, `DOCUMENT_MAX_SIZE_MB`).
  - **Automatic Speech Recognition (ASR) Pipeline**: Introduced `transcription.py` supporting local speech-to-text via `faster-whisper` (bundled PyAV/FFmpeg, zero system binary dependency for audio) and remote `openai-compat` endpoints; video containers automatically demux into a 16 kHz mono audio track for transcription plus optional keyframes for OCR.
  - **Timestamped Knowledge Chunking**: Spoken transcripts are partitioned into time-coded segments (`[HH:MM:SS - HH:MM:SS]`), normalized with speaker/temporal metadata, and indexed into dense/sparse vector stores (Qdrant) and the entity knowledge graph (Kuzu).
  - **Interactive Media Citations & Playback Seeking**: In the chat playground (`AssistantResponse.tsx`), citations derived from audio/video units display interactive timestamp badges; clicking scrolls and seeks the built-in HTML5 player (`DocumentPreview.tsx`) directly to the cited timestamp interval.
  - **Comprehensive Test Suite**: Added 730-line `test_ingestion_asr.py` covering synthetic audio generation, WAV/MP4 demuxing, faster-whisper transcription, error degradation, and caching.
- **Gemini-style composer in the chat playground**: the input textarea auto-grows with typed content (up to ~8 lines) instead of scrolling inside a fixed box, and a corner toggle (maximize/minimize icon) expands it into a large fixed-height writing area with the toolbar pinned underneath — collapsed again after sending; locale labels `composerExpand`/`composerCollapse` added to `en.json`/`vi.json`.
- **Suggested follow-up questions after every answer (like ChatGPT)**:
  - **Protobuf contract**: Allocated field `repeated string suggested_questions = 10` on `GenerateAnswerResponse` in `proto/openvie_ai_v1.proto`, regenerated Python pb2/pyi stubs, and updated the frozen-contract tests on both the Java (`ProtobufContractTest`) and Python (`test_proto_contract.py`, `test_contracts.py`) sides.
  - **AI generation**: New bounded `app/modules/generation/internal/suggestions.py` module proposes at most 3 short self-contained follow-up questions in the answer language after a grounded answer (never on chit-chat or "no information" replies); every failure degrades to an empty list and never breaks the answered turn. Suggestions ride through `AssistantMessage`, `GenerationResult`, the gRPC `completed` event, and the generation-id replay cache.
  - **Spring control plane**: `AiInferenceApi.GeneratedAnswer` and `ChatDtos.AssistantMessageResponse` gained a `suggested_questions` list that flows from the inference stream into the SSE `complete` payload; replayed idempotent turns omit them.
  - **Playground UI**: `ChatPlayground.tsx` renders up to 3 clickable suggestion chips under the latest answer and sends the chosen one as the next user message; chips disappear while a response streams. Normalized on the client alongside junk-entry filtering in `chat-api.ts` with locale strings added to `en.json`/`vi.json`.
  - **Fixed session-history reload wiping the chips**: `ChatPlayground.tsx` refetched `messages` from the DB as soon as `sending` flipped back to false, replacing the live message list — including the fresh suggestion chips — with history rows seconds after the answer completed. The shared history now loads once per session view (deferred while a turn is streaming), so the chips stay visible; unrelated sidebar refreshes are untouched.
  - **Light-mode chip hover is now legible**: the token-based hover (`hover:bg-muted/60`, `hover:border-primary/50`, lime icon) is tuned for dark surfaces — on light, card/muted differ by ~2% alpha and 50% lime washes out. `globals.css` gives light theme an explicit chip hover mirroring the light outline-button language (lime border `#c8f53c`, soft ink tint, olive icon `#527400`); dark mode keeps the token behavior untouched.

## [0.3.1] - 2026-10-22

### Added
- **Lakehouse data architecture, CDC streaming pipeline & DuckDB OLAP engine (issue #52)**:
  - **Decoupled data lifecycle**: Separated real-time operational transactions (PostgreSQL OLTP) from cold historical analytical data, satisfying DX-OS design principle (i) without placing analytical query load on the primary business database.
  - **Asynchronous CDC streaming via RabbitMQ**: Introduced `LakehouseCdcPublisher` in the Java control plane to stream business lifecycle and audit events (`cdc.events.#`) across a dedicated durable queue `openvie.lakehouse.cdc.v1`; integrated directly into `AuditLogListener` to record audit trails to cold storage post-commit without blocking transactional flows.
  - **Partitioned Snappy Parquet Lakehouse**: Built `LakehouseArchiveSink` in Python using PyArrow and Polars to write snappy-compressed, partitioned Parquet archives to SeaweedFS S3 under `lakehouse/tenants/{tenant_id}/{dataset}/year={YYYY}/month={MM}/{batch_id}.parquet` across 4 core datasets: `audit_events`, `chat_analytics`, `document_lifecycle`, and `workflow_decisions`.
  - **DuckDB embedded OLAP analytical query engine**: Implemented `DuckDbLakehouseEngine` executing read-only SQL queries directly over Lakehouse Parquet partitions. Automatically enforces tenant isolation via scoped database views (`WHERE tenant_id = ?`) and guards against destructive SQL operations (`DROP`, `ALTER`, `DELETE`, `UPDATE`, `INSERT`).
  - **Automated analytical reporting endpoints**: Added pre-aggregated summary calculation for chat turn volume, duration and token consumption (`/analytics/chat`), document ingestion counts and chunk statistics (`/analytics/documents`), audit event breakdowns by action and actor (`/analytics/audit`), and workflow SLA approval metrics (`/analytics/workflows`).
  - **Spring control plane proxy & zero-trust access control**: Added `LakehouseAnalyticsController` (`/api/v1/analytics/lakehouse/**`) in Spring Boot resolving tenant scope and permissions from authenticated JWT tokens before dispatching to the analytical engine.
  - **Comprehensive test suite**: Added 7 unit and integration tests in `test_lakehouse.py` verifying partition writing, tenant isolation, SQL safety guards, aggregation summaries, and FastAPI endpoints.
  - **Documentation alignment**: Updated `docs/DX_OS.md` (raising Principle (i) to Achieved and adding Lớp 5 & Lớp 6 to reference architecture), `docs/ARCHITECTURE.md` (topology diagram and data ownership), `docs/INTEGRATION.md` (section 9 API guide), and `README.md`.

## [0.3.0] - 2026-10-22

### Added
- **End-to-end image citations and lightbox zoom (issue #50)**:
  - **Protobuf contract**: Defined `CitationImage` message (`object_key`, `media_type`, `width`, `height`) and allocated field `optional CitationImage image = 16` in `proto/openvie_ai_v1.proto`, with updated Python pb2/pyi stubs and Maven protobuf Java bindings.
  - **Multi-modal extraction & Ingestion**: Introduced `ImageAsset` and `ParsedDocument.media` across DOCX (`word/media/*`), XLSX (`xl/media/*`), PDF embedded images, and standalone image documents; bound block-level metadata (`image_name`, `image_media_type`, `image_width`, `image_height`) without storing raw bytes in the OCR artifact JSON cache.
  - **Storage & Lifecycle**: Added deterministic storage keys in `storage_keys.py` (`document_media_prefix`, `document_media_key`); pipeline uploads media assets directly to SeaweedFS S3 under `tenants/{tenantId}/knowledge-bases/{kbId}/documents/{docId}/media/{digest}.{ext}` and cleans them up on document deletion.
  - **Index, Retrieval & Cache v3**: Propagated image fields into Qdrant vector payloads, `RetrievedKnowledgeUnit`, and `Citation`; bumped `RETRIEVAL_CACHE_SCHEMA_VERSION` and `SEMANTIC_ANSWER_CACHE_SCHEMA_VERSION` to schema version 3 with strict field validation.
  - **Spring Control Plane API**: Added `AiInferenceApi.CitationImage` record and `ChatDtos.CitationImageDto`; added authenticated `GET /api/v1/documents/{documentId}/media/{mediaId}` endpoint with 16-hex key validation and MIME detection.
  - **Frontend UI & Lightbox**: Rendered image thumbnail in cited source cards, added a prominent `citedImages` preview gallery in `AssistantResponse.tsx`, and integrated a responsive full-screen Dialog lightbox zoom modal for inspecting high-resolution diagrams.
- **ASF Apache-2.0 license standardization and verification (issue #48)**:
  - Prepended official Apache-2.0 license headers across all 561 source files (Java, Python, TS/TSX, Protobuf, Shell) with `Copyright 2026 LinkedNodeDigital`.
  - Added comprehensive third-party component attribution in `NOTICE`.
  - Pinned infrastructure container image versions (`redis:7.2-alpine`, `qdrant/qdrant:v1.19.2`, `chrislusf/seaweedfs:3.85`).
  - Added `scripts/license_headers.py` enforcement tool (`--add`, `--check`) and enforced license header verification as the first job in `.github/workflows/ci.yml`.
- **Comprehensive documentation overhaul & 3-OS support (issue #49)**:
  - Added `docs/WORKFLOW.md` documenting bilingual (VI/EN) embedded BPMN approval flows and generic engine integration.
  - Comprehensive guide for Linux, macOS, and Windows-WSL2 in `docs/DEVELOPMENT.md` and `docs/DEPLOYMENT.md` covering native Tesseract OCR via `winget`, Docker Desktop WSL2 backend, and Ollama concurrency.
  - Verified and fixed all 193 relative and heading anchor links across 20 markdown files with 0 broken links.

- **Integration surface for third-party apps (self-host, headless)**:
  - **API keys (machine-to-machine):** owner-only `POST/GET/DELETE /api/v1/admin/api-keys` creates a masked `ovk_…` credential, lists an organization's keys, and revokes one. A key provisions a **service-member user** (unusable password credential, organization role `MEMBER`) plus one workspace membership, so every downstream foreign key — uploads, chat sessions, audit — records a real user id; authentication re-resolves the live membership exactly like a user JWT, so `Authorization: Bearer ovk_…` passes every existing `@PreAuthorize` rule unchanged. Only the SHA-256 hash of the secret is stored (`V6__api_keys.sql`); the plaintext is returned exactly once, and revoking the key suspends the service account immediately.
  - **Signed webhooks (event push):** owner-only `POST/GET/DELETE /api/v1/admin/webhook-endpoints` registers per-organization POST targets with an optional event whitelist and returns the `whsec_…` signing secret once; `POST …/{endpointId}/test` journals a `webhook.test` event end to end and `GET …/{endpointId}/deliveries` exposes the 20 most recent outbox rows. Delivery follows the transactional-outbox discipline (`V7__webhooks.sql`): events journaled inside the producing transaction (`document.status.changed` from ingestion; `workflow.started|approved|rejected|escalated` through an after-commit audit bridge), then delivered asynchronously by a `SKIP LOCKED` batch dispatcher with an HMAC-SHA256 `X-OpenVie-Signature: t=<unix>,v1=<hex>` over `<timestamp>.<body>`, bounded exponential retries (30 s → 32 m, 5 attempts), and an explicit `DEAD` status; `X-OpenVie-Event-Id` is the receiver-side dedupe key. Endpoint secrets use the same `NOTIFICATION_ENC_KEY` cipher as mail-channel credentials, and outbound delivery is kill-switchable (`app.webhooks.enabled`).
  - **Generic workflow-engine API (L2):** `/api/v1/workflow/engine` exposes the Flowable engine itself rather than one application of it — deploy any BPMN 2.0 definition, list definitions, start a process with variables and an optional business key, list running processes, read a process's history (terminal state, duration, variables), and work a generic task inbox (`claim`/`complete` with variables). Every operation is scoped to the caller's workspace through Flowable native tenancy; approval-flow tasks are listed with `approvalDomain: true` but refuse raw claim/complete (409), so integrators cannot bypass the document journal. Deploy is workspace-admin/organization-owner only.
  - **Synchronous chat for server-to-server callers:** `POST /api/v1/chat/sessions/{sessionId}/messages` with `Accept: application/json` runs the exact same turn pipeline (fingerprint, idempotent replay, document-scope validation) but waits for the final answer and returns it as JSON; any other `Accept` keeps the SSE stream.
  - **`/api/v1/auth/refresh` accepts non-browser callers:** the refresh token is read from the `refresh_token` cookie, the `X-Refresh-Token` header, or a `{"refresh_token": …}` JSON body (in that order), so a machine client rotates credentials without cookie handling.
  - **Per-consumer rate limiting:** authenticated traffic is metered in Redis per `tenant:user` (`app.rate-limit.consumer-requests-per-minute`, default 600/min) with `X-RateLimit-Limit`/`X-RateLimit-Remaining` headers and a `429` + `Retry-After` at the ceiling — fail-open on a Redis outage, and SSE async re-dispatches are not double-counted.
  - `docs/INTEGRATION.md` documents the whole surface end to end (authentication, synchronous chat, webhooks and signature verification, the engine API, rate limits, and kill-switches).

- **Approval decision write-back and notification (issue #29 / #30 / #35)**:
  - Authoritative document state: Flyway migration `V8__document_approval_writeback.sql` adds `approval_status`, `approval_target`, `approval_decided_by`, and `approval_decided_at` to the `documents` table, indexed by `(tenant_id, approval_status)`. Ingestion status (`DocumentStatus`: PENDING/PROCESSING/COMPLETED/FAILED) and review state (`ApprovalStatus`: PENDING/APPROVED/REJECTED, absent when never submitted) remain orthogonal axes.
  - Seam and lifecycle: `DocumentApi` gains `markApprovalPending(tenantId, documentId, publishTarget)` and `recordApprovalDecision(tenantId, documentId, decision, decidedBy)`. Submitting for approval marks the document PENDING; the auto-approved short circuit or reviewer verdict records APPROVED or REJECTED with the actor's user id, invalidating document caches immediately.
  - Submitter verdict notifications: settling an approval process emits a decoupled `WorkflowDecisionEvent`; `NotificationListener` records a `Notification` of type `WORKFLOW_DECIDED` post-commit and sends an email via `EmailService.sendWorkflowDecisionEmail` with the verdict, approval level, and rejection reason.
  - Contract and architectural rationale documented in `docs/WORKFLOW.md`.
- **Operational observability and backup automation (issue #31 / #32 / #33)**:
  - Monitoring stack: Optional `monitoring` Compose profile (`docker-compose.prod.yml` and `docker-compose.yml`) providing Prometheus (`v2.55.1`), Alertmanager (`v0.27.0`), and Grafana (`11.3.1`) bound exclusively to loopback; ships 8 alert rules (`openvie-alerts.yml`) and 2 provisioned dashboards for platform metrics and AI inference/ingestion pipeline.
  - Gateway observability: Apache APISIX records Prometheus metrics globally and exposes `/apisix/prometheus/metrics` guarded by private-network restrictions; documented in `apisix/README.md`.
  - Backup & restore tooling: `scripts/ops/backup.sh` and `restore.sh` provide automated, sha256-verified backups of PostgreSQL (`pg_dump -Fc`), SeaweedFS object volumes, and configuration with configurable retention (`--keep N`), dry-run mode, and a documented restore drill.
- **Multi-member workspace assignment (Jira-style multi-select)**:
  - The Add Member dialog in `frontend/src/app/[locale]/(dashboard)/users/page.tsx` now supports selecting multiple members via interactive autocomplete chips with avatar previews and quick removal buttons.
  - Users can search and pick multiple accounts, or paste delimiter-separated email lists (commas, spaces, semicolons).
  - Submissions are batched across all selected users in parallel via `Promise.allSettled`, with distinct counts (`t("membersAddedCount")`) and retaining only failed items upon partial errors.
- **Bilingual error localization (Spring & FastAPI responses)**:
  - Centralized error translation in `frontend/src/lib/localize-error.ts` automatically maps backend error messages (authentication, workspace roles, document approval guards, rate limits) to the active locale (`vi` or `en`), ensuring notifications and form alerts render in Vietnamese when browsing under `/vi/` while keeping English for `/en/`.
### Changed

- **OpenAPI documentation overhaul (springdoc)**:
  - Every REST endpoint (83 across the 13 controllers) now carries `@Operation` summaries/descriptions and explicit `@ApiResponses` with the real error semantics (`SESSION_NOT_FOUND`, `IDEMPOTENCY_KEY_REUSED`, `MESSAGE_IN_PROGRESS`, `KNOWLEDGE_BASE_CHANGED`, `INVALID_DOCUMENT_SCOPE`, the workflow 403/404 paths, notification-channel 409s, …); the chat submit endpoint documents the SSE frame protocol (`content`/`reset`/`complete`/`error` frames, 5 s keepalive comments, client-disconnect cancel).
  - The bearer scheme is now declared per controller/endpoint (`bearerAuth`) instead of a single global requirement, so public auth/setup endpoints show up unlocked in Swagger UI; the Info description explains the JWT flow and that workspace (tenant), user, and role resolve from token attributes on every request — no tenant header.
  - Key request/response DTOs gained `@Schema` descriptions and examples (~25 classes: chat session/submit/citations, document status shapes, workflow decisions and SLA config, auth requests, member-management requests, the setup form) — including the `document_ids` chat scope semantics (at most 20 COMPLETED documents; empty or absent = whole knowledge base).
  - The OpenAPI `info.version` now comes from the Maven version through resource-filtered YAML (`@project.version@`) instead of the hardcoded stale `1.0.0`; production disables springdoc (`springdoc.api-docs.enabled=false`), so `/v3/api-docs` and `/swagger-ui` answer 404 in prod while dev/test keep the UI. `docs/DEPLOYMENT.md` documents the stance plus the AI worker's intentionally disabled FastAPI docs (internal, mTLS-gRPC-only service).

- **Approval authority rules (issue #8 refinement)**:
  - No ceremony where the submitter already holds the reviewing authority: a workspace admin, or the org owner (e.g. a submission into General), publishing an ordinary document with the `workspace` target is decided by their own authority in the same transaction — the process still runs its APPROVE path for uniform history, and `POST /api/v1/workflow/processes` answers `autoApproved: true` with the settled `documentStatus`. Sensitive documents always keep the review, as do `shared`/`public` targets (which route to the org owner).
  - Self-approval guard: inside a real review the submitter may withdraw (reject with a reason) or hand the task upward; a decision on an own document must come from another reviewer, except the org owner as the terminal authority of the chain, which is audited with `selfDecision=true` on the workflow decision and `selfDecision`/`selfApproved` metadata.
  - Claim enforcement: a claimed task may only be completed by its claimant; an unclaimed task of the caller's approval group is claim-on-complete. Tests that exercised the reviewed flow adjust their submitter to a plain member so the flow really queues instead of being auto-decided.

### Added
- **Approval log surface (issue #8 refinement)**:
  - `GET /api/v1/workflow/processes/{processInstanceId}/log` returns the workspace-scoped audit trail of one approval process (who submitted, claimed, approved, rejected, was reminded — with timestamps) with actor display names resolved server-side; a foreign-workspace process answers 403, an unknown 404.
  - The approvals page renders this trail as a per-process log dialog with localized action labels, decision/reason/self-decision extras, and localized status chips instead of raw enum text; decision buttons appear only on tasks the current user has claimed.
  - The documents page derives each document's approval state from the process journal: documents already in review or approved show a status chip instead of re-offering a submit action the backend would refuse (rejected documents may be resubmitted), and the delete action also trusts the organization owner, not just the workspace admin.
- **Client-side document preview for every stored file type**:
  - Zero backend surface: previews fetch the original file through the existing `GET /api/v1/documents/{id}/download` and render it entirely in the browser — PDF in the native viewer, DOCX through `docx-preview`, XLSX through `read-excel-file/browser` (tabbed per sheet), CSV through a quote-aware self-hosted parser with delimiter detection, plain text, and markdown/HTML inside a sandboxed iframe (`sandbox=""`, scripts inert) with a shared stylesheet.
  - The document detail page toggles between the extracted-data viewer ("Nhận diện") and the original preview ("Bản gốc"); the approvals page adds a preview action on inbox tasks and submission rows opening the file in a wide dialog, so reviewers see the real content before deciding.
  - Two new open-source-only dependencies (`docx-preview`, `read-excel-file`) are lazy `import()`ed, so heavy renderer code loads only when such a document is actually previewed; unsupported extensions fall back to a download button.
- **Evidence anchoring inside the original preview**:
  - The citation bar now works in BOTH viewer modes ("Nhận diện" and "Bản gốc"); selecting a unit scrolls to and highlights it in the rendered original file — plain text renders a `<mark>` around the matched span, DOCX and markdown/HTML frames are marked inside their DOM (`sandbox="allow-same-origin"` keeps embedded scripts inert while letting the pane mark and scroll), XLSX switches to the evidence sheet and highlights its rows (cell-range hint like `B4:F9` first, otherwise letters+digits row matching, which also flattens table chunks like `A | B`), and CSV highlights matched rows.
  - PDF is now rendered through pdf.js (`pdfjs-dist`, lazy-imported) instead of the native iframe viewer: pages draw lazily via IntersectionObserver at fit-width scale, and the anchored chunk draws proportional overlay rectangles over the text layer, starting the scan on the indexer's `page_number` hint and falling back to a plain-text scan of every page.
  - Matching is best-effort (`src/lib/preview-citations.ts`, fully unit-tested): an indexer character-window hint (`source_start/source_end`) when valid, otherwise a whitespace-collapsed search with head/tail anchoring plus a verify-and-narrow pass — 17 new tests cover the projections, DOM marking/unmarking, PDF interval→rect math, A1 range parsing, and spreadsheet row matching (83 frontend tests green overall).
  - The PDF worker comes from the installed `pdfjs-dist` package: `npm` scripts
    (`dev`/`build`) copy `pdf.worker.min.mjs` into `public/` via
    `frontend/scripts/prepare-pdf-worker.mjs`, so the runtime always matches the
    declared dependency (no third-party CDN). *(History: v0.3.x vendored the
    worker file into the repository; v1.0.0 removed that copy.)*
- **Pagination for the secondary list pages**:
  - The team members, approvals (task inbox + submissions), admin organization, and admin workspace lists render through a shared client-side pager (`components/ui/list-pager.tsx`) reusing the documents page's canonical `PaginationControls` bar — page/size 10/20/50, localized via the existing `ListControls` strings. Backend list endpoints still return whole arrays, so paging stays client-side (documents keeps its URL-param server paging); the bar is hidden while the list fits on one page, and a shrinking list never blanks the current page because the page number is clamped.
  - The approval journal grew too fast for client paging, so `GET /api/v1/workflow/processes` now accepts optional `page`/`size` (0-based page, size 1–100, default 20; outside the bounds → 400) and answers with the same list body plus an `X-Total-Count` header — no params keeps the unchanged full list for the document badge map. The approvals page fetches its submissions through the new server-paged window with `page`/`size` in the URL (1-based, clamp-redirect past the last page), while the task inbox stays client-paged; the Flowable query is built twice to count and slice instead of reusing one query object.
- **Document-scoped RAG answers (pick the documents the AI answers from)**:
  - The chat contract carries an optional document scope end to end: `POST /api/v1/chat/sessions/{id}/messages` accepts `document_ids` (max 20); the Java control plane validates every id as a COMPLETED document of the session's knowledge base (403-class BadRequest otherwise), folds the scope into the turn fingerprint so idempotent replays keep their scope, and forwards it through the `GenerateAnswerRequest` gRPC field; absent/empty keeps whole-knowledge-base retrieval.
  - The AI worker threads the scope into retrieval (`RetrievalQuery.document_ids` with Qdrant payload filtering) and keys semantic answer-cache entries per scope (`visible_document_ids`), deduplicating and leaving `None`/empty as unscoped; the modules below (retrieval cache, hybrid retriever, knowledge-graph search) already honored per-document filters.
  - The playground composer gains a scope control next to "Thêm nguồn": a liquid-glass popup (`components/chat/DocumentScopeDialog.tsx` — bottom sheet on mobile, centered panel from `sm:` up, explicit light/dark theming via the shared `useTheme` hook) with search, indexed-status gating, a 20-document cap and per-scope answer-cache semantics; "Chat with document" on the document detail page opens the chat pre-scoped to that document.

### Fixed
- The workspace member role dropdown on the team page showed the raw backend enum (`MEMBER`/`WORKSPACE_ADMIN`) in its closed trigger: the Base UI `Select.Value` renders the raw value rather than the selected item's label, so the localized label is now passed explicitly — matching the pattern already used by the visibility select and the add-member dialog.
- Loaded process variables via `.includeProcessVariables()` in `listTasks` TaskQueries so task cards in the approval inbox render the document file name and enable document preview.
- Fixed `AssistantResponse` lightbox modal layout by replacing `grid` with `flex flex-col sm:max-w-4xl overflow-hidden`, truncating long OCR snippet headers, and centering the responsive full-resolution image within the dialog.
- Fixed `npm ci` failures in GitHub Actions CI by restoring `@swc/helpers@0.5.23` in `frontend/package-lock.json`.

### Added

- **Two-level document approval workflow (issue #8)**:
  - Embedded Flowable 8 BPMN engine in the Spring Boot `api` module, sharing the existing PostgreSQL (engine-managed `ACT_*`/`FLW_*` tables next to the business schema — no additional container); process `eofficeDocumentApproval` with synchronous sensitivity scoring, level routing, and SLA boundary timers.
  - Workspace REST surface under `/api/v1/workflow`: start a process (member submits only), list processes/tasks, claim a task (one assignee per task; the candidate-group queue keeps it visible as backup), approve, reject (reason is mandatory), and an explicit level-1→level-2 hand-off — skipping a level is not offered; a task or process of another workspace answers with an explicit 403.
  - RBAC-aware approval levels set by the system, not the submitter: documents matched by the shared sensitivity policy or targeting `shared`/`public` publish routes are approved at level 2 by the organization owner only, ordinary workspace documents at level 1 by workspace admins; SSO-issued tokens work because every call resolves the live workspace membership.
  - Shared sensitivity contract `contracts/approval/v1/sensitive-policy.json` (`sensitive-keyword-v1`) dual-consumed by the Python `sensitive_policy` router and the Java scorer (byte-identical runtime copy in `api/src/main/resources/approval/`); cross-language sync tests assert the term set and all 18 canonical fixtures on both sides — Java keeps no second keyword list.
  - Per-organization SLA tuning: `PUT /api/v1/workflow/sla-config` (org owner only) persists reminder/escalation minutes in `workflow_sla_configs` (Flyway V5); the 24h reminder timer is non-interrupting, the 48h escalation timer moves an overdue level-1 task to the owner queue, both escalated paths land at level 2 and record audit entries (`overdue`, never a manual decision). Defaults stay in `openvie.workflow.*` (ISO-8601 durations), demo-shortenable to minutes.
  - Audit trail of every workflow transition via `AuditLogEvent` (`WORKFLOW_STARTED`, `WORKFLOW_TASK_CLAIMED`, `WORKFLOW_APPROVED`, `WORKFLOW_REJECTED`, `WORKFLOW_ESCALATED`, `WORKFLOW_REMINDER_SENT`, `WORKFLOW_SLA_CONFIG_CHANGED`) with `POLICY_VERSION`, level decision, and decision reason recorded as process variables.
  - Durable state proof: integration tests restart a full application context over the same database and rediscover the running process, its claimed task, and finish the approval without replay.

### Removed
- Removed obsolete developer-plan ADRs (`ADR-0001`, `ADR-0002`) and duplicate `docs/WORKFLOW_DEMO.md` in favor of product behavior documentation in `docs/WORKFLOW.md`.

## [0.2.3] - 2026-10-19

### Added

- **Transactional Email Suite**:
  - New best-effort notice emails that never block membership: a workspace-added notice when an administrator adds an existing account to a workspace, and an account-created notice when an administrator creates an account (the email carries no password — the administrator shares the temporary password directly, and the first sign-in forces a password change).
  - Shared dark-brand email shell mirroring the app theme (near-black card, lime primary button) with the PNG brand logo on top, because common webmail clients do not render SVG; all user-supplied text is HTML-escaped.
  - `LOGIN_LINK` and `LOGO_URL` configuration; an unset `LOGO_URL` derives `/logo.png` from the login link's origin, so a deployment that configured `LOGIN_LINK` gets a working logo without extra configuration.
  - SMTP notification channel management in the admin settings console: add/edit with write-only secrets (blank fields keep the stored values on update), enable/disable, delete, and test-send.
  - Add-member autocomplete: new `GET /api/v1/users/suggest` endpoint returning active organization accounts that match the typed fragment and are not yet members of the workspace, gated to workspace admins, discarding LIKE wildcards, and capped at 8 results; the workspace Users add-member dialog offers debounced suggestions that fill the email field.
  - Temporary password generation in the admin create-user dialog: crypto-random 16 characters with a guaranteed character-class mix, plus a show/hide toggle so the administrator can share the secret out of band.

### Changed

- Replaced `frontend/public/logo.png` with a new raster brand asset for email rendering.
- Workspace create dialog visibility select is state-driven so its trigger shows the translated label instead of the raw enum value.
- Bilingual dictionaries updated: invitation strings removed and suggest/password-generation strings added.

### Removed

- **SendGrid and Brevo API-key email providers**:
  - Deleted `SendGridEmailProvider` and `BrevoEmailProvider` with their tests; notification channel types are limited to `smtp`, and `NotificationChannelConfig` plus channel requests drop the `apiKey` field.
  - Dropped the `com.sendgrid`/`com.brevo` Maven dependencies, the `spring.sendgrid`/`spring.brevo` YAML keys, and the `SENDGRID_API_KEY`/`BREVO_API_KEY` environment variables from templates.
- Disabled workspace invitation UI remnants on the Users page (commented handlers, form state, and strings).
- **Qwen model provider in the AI runtime**:
  - Removed `QwenChatModel` — the externally managed MLX/OpenAI-compatible chat endpoint adapter — including its OpenAI-compatible payload parsing and SSE stream handling; `create_chat_model` returns only the Ollama adapter and stream parsing is Ollama NDJSON-only.
  - Restricted `LLM_PROVIDER` to `ollama`; removed the MLX/Qwen alternative block from `rag-chatbot-fastapi/.env.example` and the `qwen` chat-adapter rows from `docs/RETRIEVAL.md` and `rag-chatbot-fastapi/GUIDE.md`.
  - Trimmed the qwen test suites and fakes from the model gateway, config, and semantic answer cache tests.

### Fixed

- Select dropdown item focus and highlight states follow the active theme tokens instead of the hard-coded accent color classes.

## [0.2.2] - 2026-10-18

### Changed

- **Unified OpenVie Technical Naming & Packaging**:
  - Relocated Java API packages from `com.cacanode.api` to `com.openvie.api` across all 266 Java source files (`src/main/java` and `src/test/java`).
  - Updated Maven `groupId` to `com.openvie` and `finalName` to `openvie-api` in `api/pom.xml`.
  - Renamed Protobuf package from `cacanode.ai.v1` to `openvie.ai.v1`, `java_package` to `com.openvie.ai.v1`, and outer class to `OpenVieAiProto` in `proto/openvie_ai_v1.proto` (renamed from `cacanode_ai_v1.proto`).
  - Regenerated Python gRPC/Protobuf bindings (`openvie_ai_v1_pb2*`) and updated all transports, clients, and tests in `rag-chatbot-fastapi`.
  - Renamed RabbitMQ exchanges and queues across Spring and FastAPI to `openvie.ingestion.v1`, `openvie.dlx.v1`, `openvie.document.ingestion.v1`, `openvie.document.status.v1`, and their corresponding DLQs.
  - Renamed Prometheus metrics catalog in `rag-chatbot-fastapi` from `cacanode_*` to `openvie_*` (`openvie_ai_*`, `openvie_cache_*`, `openvie_redis_*`, `openvie_semantic_answer_cache_*`) and in Spring `CacheMetrics` to `openvie.cache.*` and `openvie.redis.*`.
  - Updated SeaweedFS S3 default bucket to `openvie` and Kuzu database path to `./data/kuzu/openvie.kuzu`.
  - Standardized Docker Compose definitions (`docker-compose.yml`, `docker-compose.prod.yml`) and `.env` templates to `POSTGRES_DB: openvie`, `POSTGRES_USER: openvie`, `SEAWEEDFS_BUCKET: openvie`, and `openvie.kuzu`.
  - Updated JSON Schema contract identifiers (`contracts/document-ingestion/v1/`) to `https://openvie.local/...`.
  - Updated default development credentials in `TenantCredentialService` and `SetupFlowTest` to `OpenVie@123`.
  - Updated OpenAPI service titles in `application-dev.yaml` and `application-test.yaml` to `OpenVie API`.

### Removed

- Removed legacy `cacanode_ai_v1_pb2*` Python generated files from `rag-chatbot-fastapi/app/generated/`.
- Removed stale `cacanode_ai_api.egg-info/` distribution metadata.

## [0.2.1] - 2026-10-18

### Added

- **Sidebar Chat History & Session Management**:
  - `ChatHistoryPanel` rendered by `AppShell` on every page: lists, debounced search, and hiding of chat sessions with a confirmation dialog, plus a last-loaded snapshot cache keyed by user.
  - Session logic extracted from `ChatPlayground` into `src/lib/chat-events.ts`: event bus for new-chat requests, session selection, active-session notification, and history invalidation.
  - `sessionStorage` handoff (`openvie.chat.pending-new`, `openvie.chat.pending-session`) so a new chat or a session selection started from another route is applied when the chat surface mounts.

- **Accessible List Controls**:
  - `FilterSelect` reimplemented as a keyboard-operable custom listbox; the native page-size `<select>` replaced by a `PageSizePicker` menu.
  - Locale-aware `DatePicker` with day/month/year views, minimum-date constraint, and "Clear"/"Today" footer, replacing native date inputs in `DateRangeFields`.

- **Responsive List Surfaces**:
  - Mobile card layouts for document lists, workspace members, workspaces, and workspace users, with tables rendering from `sm` upward only.

- **Theme & Shell Polish**:
  - `src/lib/theme-transition.ts` cross-fades theme changes through the View Transitions API, falling back to an immediate update under `prefers-reduced-motion` or unsupported browsers.
  - Toasts follow the active theme via `ThemeToaster` and the `openvie:themechange` event; the auth layout reads the stored theme with `useSyncExternalStore` for deterministic hydration.
  - Mobile sidebar drawer: explicit topbar toggle, slide-in animation with staggered items, and a blurred backdrop.
  - Animated dashed upload drop-zone border (`ov-upload-border`) on the documents page.

- **Workflow Engine Decision Record**:
  - `docs/adr/ADR-0001-workflow-engine-flowable.md` selecting Flowable OSS (Apache-2.0) over n8n Community (Sustainable Use License) for the v0.3.0 e-Office approval flow, covering evaluation criteria, consequences, remaining spikes, and references; linked from `README.md`, `docs/README.md`, and the DX/OS capability tables.

### Changed

- Components migrated from hard-coded slate/indigo utilities to design tokens (`bg-primary`, `text-muted-foreground`, `border-border`, `bg-muted`, …), removing the indigo compatibility overrides and consolidating light-theme rules.
- Dialog portals re-declare the app token palette so Tailwind utilities inside portaled dialogs follow the active theme; dialog surfaces use themed glass panels with a blurred overlay.
- Liquid glass dropdowns are now near-opaque surfaces with reduced blur for legibility over dense content.
- Tables use `text-xs` and tighter cell padding on small screens.
- Chat answers render on theme tokens, wrap long unbroken tokens, and use themed code-block surfaces.
- Workspace switcher drops its cached workspace list and refetches when `openvie:workspaceschange` is announced by admin create/rename/visibility/archive actions.
- Bilingual dictionaries updated: the `historyWillAppear` placeholder is removed, `listControls.today` and `documents.search` are added.
- Next.js `devIndicators` disabled so the dev badge no longer overlaps the app shell during phone QA.

### Fixed

- Chat switching works again: selecting a session or requesting a new chat from another route is applied on arrival, and the history panel highlights the open session.
- Navigation indicator repositions when the sidebar or its contents resize (`ResizeObserver`), not only on window resize and path changes.
- SSO callback screen uses the shared auth card styles, so its error state and spinner follow the theme instead of inlined colors.
- Auth pages replace the fixed `100dvh` clipping container with a scrollable page so the card stays reachable on short viewports.

## [0.2.0] - 2026-10-16

### Added

- **Keycloak OIDC Single Sign-On (SSO)**:
  - Keycloak 26 identity provider provisioned in development (`docker-compose.yml`) and production (`docker-compose.prod.yml`) Compose stacks with dedicated PostgreSQL persistence.
  - Pre-configured `openvie` realm (`keycloak/realm/openvie-realm.json`) with public OIDC client (`openvie-web`), PKCE support (`S256`), and standard identity claims (email, name, preferred_username, sub).
  - Reverse-proxy routes on APISIX edge gateway for Keycloak endpoints (`/realms/*`, `/resources/*`, `/js/*`, `/robots.txt`) with upstream host forwarding and WebSocket upgrade support.
  - Spring Boot SSO bridge: `POST /api/v1/auth/sso/exchange` endpoint exchanging PKCE authorization code for OpenVie JWT access and refresh tokens.
  - Automatic identity linking: associates Keycloak identity (`keycloak_subject`) with existing OpenVie user accounts when verified email matches, preserving assigned roles (`ORG_OWNER`, `WORKSPACE_ADMIN`, `MEMBER`).
  - Database schema migration (`V4__sso_identity_link.sql`) adding `keycloak_subject` to `users` and `sso_auto_provision`, `sso_email_domains` to `organizations`.
  - Frontend PKCE client flow (`src/lib/oidc.ts`) and callback route (`/sso/callback`) with state verification, code exchange, and workspace navigation.
  - "Continue with SSO" login option on authentication page alongside standard password authentication.
  - Comprehensive Keycloak architecture, deployment, and configuration guide in `docs/KEYCLOAK.md`.

- **Dedicated Admin Surface (`ORG_OWNER`)**:
  - Isolated `(admin)` route group protected by `ProtectedAdminShell` enforcing `ORG_OWNER` role on all `/admin/*` routes.
  - Organization administration console at `/admin/organization` for organization-level profile, member roles, security, and SSO configurations.
  - Workspace management moved to `/admin/workspaces` for centralized workspace administration across the organization.
  - Organization settings console at `/admin/settings`.
  - Backend `AdminUserController` (`/api/v1/admin/users/*`) restricting organization-level member management and invitations to `ORG_OWNER`.
  - Dual-mode navigation separating workspace operations from organization governance.
  - Backwards-compatible Next.js redirects from legacy `/organization`, `/workspaces`, and `/settings` routes to `/admin/*`.

- **Theme Engine & UI Modernization**:
  - Theme system supporting Light, Dark, and System modes with custom `useTheme` hook, local storage persistence, and media query synchronization.
  - Synchronous `public/theme-init.js` injection to eliminate theme flash (FOUC) during SSR hydration.
  - Modernized visual styling transitioning from indigo accents to an emerald/green color palette across all views.
  - Accessible theme toggle controls across application shells and settings.
  - Liquid glass dropdown menus and enhanced glassmorphism styling for navigation menus.
  - Vector SVG branding (`openvie-logo.svg`, `icon.svg`) replacing raster favicon.
  - `AppLoadingScreen` transition component for smoother authentication and workspace navigation state changes.
  - Interactive confirmation dialogs for destructive user actions (member removal, role adjustments, workspace deletion).

### Changed

- User management endpoint `/api/v1/users` is now strictly workspace-scoped, delegating organization-level user administration to `/api/v1/admin/users`.
- App shells, workspace switcher, and documentation components refactored to consume theme tokens and responsive layouts.
- Bilingual localization dictionaries (`messages/en.json`, `messages/vi.json`) expanded for SSO flows, admin navigation, theme toggles, and confirmation modals.
- MVP Roadmap in `README.md` updated to mark `v0.2.0` (Keycloak SSO) as released.

### Fixed

- Authentication layout theme initialization avoids redundant React state updates and hydration warnings.
- Member role updates in user management table operate cleanly outside form submit lifecycles.

## [0.1.1] - 2026-10-07

### Added

- Next.js rewrites same-origin `/api/v1/*` requests to the API for gateway and tunnel development.
- Conservative Vietnamese/English greeting and nonsense gate with history-aware, citation-free replies that skip answer caches, embeddings, retrieval, and model calls.
- Native Ollama NDJSON and OpenAI-compatible SSE answer streaming through gRPC and authenticated Spring SSE; provisional drafts are polished and only committed, validated final answers expose sources.
- Subtle writing indicators, live cursor, final-source transition, reduced-motion support, and answer cancellation in the chat UI.

### Changed

- Next.js development uses Webpack instead of Turbopack.
- `GenerateAnswer` uses server-streaming `GenerateAnswerEvent`; chat message POST responses use `content`, `reset`, `complete`, and `error` SSE events instead of buffered JSON. Spring and Python must be deployed together; the unary answer contract is removed.
- Default answer output budget increases from 512 to 1024 tokens across runtime, development, and deployment configuration; graph extraction remains at 512 tokens.
- Contribution and security guides now include Vietnamese alongside English.

### Fixed

- APISIX web routes forward the upstream host and support WebSocket upgrades.
- Explicit Next.js development WebSocket origins and Spring development CORS origins allow the tunneled login page to hydrate and its authentication requests to reach the API.
- Frontend authentication defaults to the same-origin `/api/v1` path instead of a legacy absolute URL fallback.
- Login links to self-registration only when the organization enables it; signup includes credentials so its session can be retained.
- Maven incremental builds retain generated protobuf/gRPC sources and classes so `make -C api dev` starts after repeated builds.
- Chat SSE disables intermediary transformation/compression to prevent the Next.js proxy from buffering previews until completion.
- Revision retries reset provisional content; cancellation before commit fails the turn and stops provider work without persisting a partial answer.

## [0.1.0] - 2026-10-06

### Added

- **Platform Architecture**:
  - Initial public pre-release of OpenVie, a Vietnamese-first self-hosted RAG platform.
  - Three-plane runtime architecture: Spring Boot 21 business control plane, Python FastAPI AI inference and ingestion runtime, and Next.js 16 web console.
  - Apache APISIX standalone edge gateway (:8088) with per-IP rate limiting (`limit-req`) on authentication and business endpoints.

- **Access Control & Identity**:
  - Three-tier role-based access control: `ORG_OWNER`, `WORKSPACE_ADMIN`, and `MEMBER`.
  - Organization and workspace isolation with multi-tenant data scoping across PostgreSQL, Qdrant, and Kuzu.
  - Password-only authentication with JWT rotation and offline owner recovery (`make recover-owner`).
  - One-time initial web setup wizard (`/setup`).

- **Asynchronous Ingestion & Document Vault**:
  - Asynchronous ingestion workers with RabbitMQ transport and Redis atomic lease checkpoints.
  - Multi-format document parsing: PDF (`pypdf`), Word (`python-docx`), Markdown, HTML, plain text, and spreadsheets (CSV, XLSX).
  - Provenance-preserving deterministic chunker with table row preservation and repeated headers.
  - Object storage integration with SeaweedFS S3.

- **Hybrid Retrieval & RAG Pipeline**:
  - Dense text embeddings with Ollama (`bge-m3`, 1024 dimensions).
  - Lexical sparse search with FastEmbed BM25 (`Qdrant/bm25`).
  - Evidence-grounded knowledge graph traversal with Kuzu.
  - Profile-weighted Reciprocal Rank Fusion (RRF) across dense, sparse, and graph channels.
  - Optional cross-encoder reranking via Text Embeddings Inference (TEI).
  - Structural table sibling completion (`TableQuery`) ensuring full multi-row table context in RAG answers.

- **Conversational Intelligence**:
  - Contextual query planning (`ContextualQueryPlanner`): automatic reformulation of anaphoric follow-up questions into standalone search queries.
  - Complete enumeration grounding with bullet-list guidance in system prompts.
  - Deterministic answer polishing (`answer_polish`) replacing bracketed placeholders and stripping document filename echoes.
  - Structured citation verification ensuring only authorized source units are returned.

- **Deployment & Developer Experience**:
  - Full-stack production Compose deployment (`docker-compose.prod.yml`) orchestrating the entire platform in containers.
  - Development Compose stack (`docker-compose.yml`) for local infrastructure backing services.
  - Fully bilingual documentation (Vietnamese and English) across `README.md`, `docs/`, `api/GUIDE.md`, and `rag-chatbot-fastapi/GUIDE.md`.
