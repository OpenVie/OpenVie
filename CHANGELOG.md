# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
