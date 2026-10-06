# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
