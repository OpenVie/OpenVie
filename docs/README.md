# OpenVie documentation

[Project overview](../README.md) · [Local development](DEVELOPMENT.md)

Start with the guide for your task.

## New developer reading path

1. Read the [project overview and repository map](../README.md).
2. Follow [local development](DEVELOPMENT.md) to configure, start, and check the stack.
3. Read [architecture](ARCHITECTURE.md) for service ownership and cross-service boundaries.
4. Follow the guide for the code you will change:
   - [Java module guide](../api/GUIDE.md): public module contracts, events, and table ownership.
   - [Python module guide](../rag-chatbot-fastapi/GUIDE.md): capability modules, transports, and runtime roles.
   - [Web development and checks](DEVELOPMENT.md#start-the-applications).
5. Consult the feature reference only when your change needs it.
6. Read the [DX-OS / Open-Core mapping](DX_OS.md) before presenting or comparing this repository
   with the three-tier DX-OS architecture.
7. Review the [open-source and distribution boundary](OPEN_SOURCE.md) before exporting a public repository.

## Guides by task

| I want to… | Read |
| --- | --- |
| Run locally, understand configuration, or execute checks | [Local development](DEVELOPMENT.md) |
| Understand service ownership, storage, security, or runtime sequences | [Architecture](ARCHITECTURE.md) |
| Change document parsing, indexing, models, or retrieval | [Ingestion and retrieval](RETRIEVAL.md) |
| Configure a self-hosted release or plan verification and recovery | [Deployment](DEPLOYMENT.md) |
| Expose one HTTP entry point or rate-limit traffic at the edge | [Deployment §9 — Edge gateway](DEPLOYMENT.md#9-edge-gateway-apache-apisix) |
| Self-host, assess licensing, or publish a reviewed source export | [Open-source and distribution boundary](OPEN_SOURCE.md) |
| Map this repository against the DX-OS / Open-Core architecture and its H-P-D-I spaces | [DX-OS mapping](DX_OS.md) |

## What belongs where

- **Root README:** orientation, current scope, repository map, and links. Not the full API manual.
- **Service guides:** language-specific implementation and module ownership rules. These stay beside the code.
- **Development guide:** setup, configuration sources, command entry points, and local troubleshooting.
- **Topic references:** detailed current behavior, examples, boundaries, and implementation links.

## Implementation status and evidence

“Implemented” means the repository contains a code path, not that the feature is enabled,
configured, load-tested, or approved for production. In particular:

- Optional caches require explicit configuration and rollout evidence.
- General image/audio/video ingestion is not implemented; only the documented text-bearing
  formats are processed.
- Model fine-tuning/adaptation is proposed work, not an existing training pipeline.
- A health response, fixture, or historical smoke artifact is not proof of full workflow correctness.

Use [RETRIEVAL.md](RETRIEVAL.md) for delivered versus planned ingestion behavior, and
[DEPLOYMENT.md](DEPLOYMENT.md) for verification requirements on a self-hosted install.

## Naming during the rebrand

OpenVie is the user-facing product name. Internal package/protobuf names, deployment resources,
routes, credential prefixes, and existing domains intentionally retain their current identifiers;
technical names still containing `cacanode` or `ccn` match the code they document. This
presentation rebrand does not change integration contracts or establish a new public domain.
Change executable examples in the same change as their implementation.

OpenVie application source is Apache-2.0 under LinkedNodeDigital copyright; see
[LICENSE](../LICENSE), [NOTICE](../NOTICE), and the [publication gate](OPEN_SOURCE.md#publication-gate).

## Maintaining these docs

1. Give a topic one canonical home and link to it instead of copying configuration catalogs.
2. Verify current-behavior statements against source, schemas, Makefiles, package scripts, and deployment configuration.
3. Separate implemented behavior, disabled features, operational requirements, and future proposals.
4. When moving a section, update relative source links, headings, navigation, and inbound links.
5. Verify rendered Markdown, diagrams, and command entry points; report any runtime/provider checks not exercised.

Shared wire-format definitions remain in [`contracts/`](../contracts/) (JSON schemas and fixtures)
and [`proto/`](../proto/) (gRPC). Documentation explains those boundaries; it does not replace their
machine-readable definitions or compatibility checks.
