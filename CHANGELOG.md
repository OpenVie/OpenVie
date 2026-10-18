# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
