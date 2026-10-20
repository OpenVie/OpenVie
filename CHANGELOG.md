# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **OpenAPI documentation overhaul (springdoc)**:
  - Every REST endpoint (67 across the 10 controllers) now carries `@Operation` summaries/descriptions and explicit `@ApiResponses` with the real error semantics (`SESSION_NOT_FOUND`, `IDEMPOTENCY_KEY_REUSED`, `MESSAGE_IN_PROGRESS`, `KNOWLEDGE_BASE_CHANGED`, `INVALID_DOCUMENT_SCOPE`, the workflow 403/404 paths, notification-channel 409s, …); the chat submit endpoint documents the SSE frame protocol (`content`/`reset`/`complete`/`error` frames, 5 s keepalive comments, client-disconnect cancel).
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
  - `public/pdf.worker.min.mjs` is vendored into the repo and must be kept in sync with the installed `pdfjs-dist` version when upgrading (no third-party CDN).
- **Pagination for the secondary list pages**:
  - The team members, approvals (task inbox + submissions), admin organization, and admin workspace lists render through a shared client-side pager (`components/ui/list-pager.tsx`) reusing the documents page's canonical `PaginationControls` bar — page/size 10/20/50, localized via the existing `ListControls` strings. Backend list endpoints still return whole arrays, so paging stays client-side (documents keeps its URL-param server paging); the bar is hidden while the list fits on one page, and a shrinking list never blanks the current page because the page number is clamped.
  - The approval journal grew too fast for client paging, so `GET /api/v1/workflow/processes` now accepts optional `page`/`size` (0-based page, size 1–100, default 20; outside the bounds → 400) and answers with the same list body plus an `X-Total-Count` header — no params keeps the unchanged full list for the document badge map. The approvals page fetches its submissions through the new server-paged window with `page`/`size` in the URL (1-based, clamp-redirect past the last page), while the task inbox stays client-paged; the Flowable query is built twice to count and slice instead of reusing one query object.
- **Document-scoped RAG answers (pick the documents the AI answers from)**:
  - The chat contract carries an optional document scope end to end: `POST /api/v1/chat/sessions/{id}/messages` accepts `document_ids` (max 20); the Java control plane validates every id as a COMPLETED document of the session's knowledge base (403-class BadRequest otherwise), folds the scope into the turn fingerprint so idempotent replays keep their scope, and forwards it through the `GenerateAnswerRequest` gRPC field; absent/empty keeps whole-knowledge-base retrieval.
  - The AI worker threads the scope into retrieval (`RetrievalQuery.document_ids` with Qdrant payload filtering) and keys semantic answer-cache entries per scope (`visible_document_ids`), deduplicating and leaving `None`/empty as unscoped; the modules below (retrieval cache, hybrid retriever, knowledge-graph search) already honored per-document filters.
  - The playground composer gains a scope control next to "Thêm nguồn": a liquid-glass popup (`components/chat/DocumentScopeDialog.tsx` — bottom sheet on mobile, centered panel from `sm:` up, explicit light/dark theming via the shared `useTheme` hook) with search, indexed-status gating, a 20-document cap and per-scope answer-cache semantics; "Chat with document" on the document detail page opens the chat pre-scoped to that document.

### Fixed
- The workspace member role dropdown on the team page showed the raw backend enum (`MEMBER`/`WORKSPACE_ADMIN`) in its closed trigger: the Base UI `Select.Value` renders the raw value rather than the selected item's label, so the localized label is now passed explicitly — matching the pattern already used by the visibility select and the add-member dialog.

### Added

- **Two-level document approval workflow (issue #8)**:
  - Embedded Flowable 8 BPMN engine in the Spring Boot `api` module, sharing the existing PostgreSQL (engine-managed `ACT_*`/`FLW_*` tables next to the business schema — no additional container); process `eofficeDocumentApproval` with synchronous sensitivity scoring, level routing, and SLA boundary timers.
  - Workspace REST surface under `/api/v1/workflow`: start a process (member submits only), list processes/tasks, claim a task (one assignee per task; the candidate-group queue keeps it visible as backup), approve, reject (reason is mandatory), and an explicit level-1→level-2 hand-off — skipping a level is not offered; a task or process of another workspace answers with an explicit 403.
  - RBAC-aware approval levels set by the system, not the submitter: documents matched by the shared sensitivity policy or targeting `shared`/`public` publish routes are approved at level 2 by the organization owner only, ordinary workspace documents at level 1 by workspace admins; SSO-issued tokens work because every call resolves the live workspace membership.
  - Shared sensitivity contract `contracts/approval/v1/sensitive-policy.json` (`sensitive-keyword-v1`) dual-consumed by the Python `sensitive_policy` router and the Java scorer (byte-identical runtime copy in `api/src/main/resources/approval/`); cross-language sync tests assert the term set and all 18 canonical fixtures on both sides — Java keeps no second keyword list.
  - Per-organization SLA tuning: `PUT /api/v1/workflow/sla-config` (org owner only) persists reminder/escalation minutes in `workflow_sla_configs` (Flyway V5); the 24h reminder timer is non-interrupting, the 48h escalation timer moves an overdue level-1 task to the owner queue, both escalated paths land at level 2 and record audit entries (`overdue`, never a manual decision). Defaults stay in `openvie.workflow.*` (ISO-8601 durations), demo-shortenable to minutes.
  - Audit trail of every workflow transition via `AuditLogEvent` (`WORKFLOW_STARTED`, `WORKFLOW_TASK_CLAIMED`, `WORKFLOW_APPROVED`, `WORKFLOW_REJECTED`, `WORKFLOW_ESCALATED`, `WORKFLOW_REMINDER_SENT`, `WORKFLOW_SLA_CONFIG_CHANGED`) with `POLICY_VERSION`, level decision, and decision reason recorded as process variables.
  - Durable state proof: integration tests restart a full application context over the same database and rediscover the running process, its claimed task, and finish the approval without replay.## [0.2.3] - 2026-10-19

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
