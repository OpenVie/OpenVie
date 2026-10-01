# OpenVie source and distribution boundary

[Project overview](../README.md) · [Architecture](ARCHITECTURE.md) · [Local development](DEVELOPMENT.md)

**Publication status:** the first-commit cut (contract freeze, out-of-scope removal,
fresh-install baseline, and the end-to-end, security, alignment, and publication-safety gates)
is complete and recorded in the initial commit history. The boundary below describes the
intended licensing and repository split.

## Repository boundary

This directory is the **open-source application repository**. Its application source is
licensed under [Apache License 2.0](../LICENSE), with attribution in [NOTICE](../NOTICE).
Anyone may self-host, fork, or offer hosting under that license; nothing in this repository is
conditional on a separate service, subscription, or agreement. This repository does not grant
any hosted service or service-level agreement, and no separate proprietary enterprise code is
included here.

The Apache license does not grant rights to the OpenVie name or logo as a trademark beyond
customary origin attribution. Dependencies, container images, hosted services, model weights,
and datasets retain their own licenses. Model IDs in configuration do not bundle or license the
weights.

## Self-hosting boundary

Start with [local development](DEVELOPMENT.md) for a non-production stack. Spring Boot owns
authoritative organization and workspace identity, authorization, chat, and PostgreSQL state. FastAPI owns
retrieval and derived indexes; RabbitMQ, SeaweedFS, Qdrant, Kuzu, Redis, and configured model
services supply the other runtime dependencies. [Deployment](DEPLOYMENT.md) describes a
single-host self-installation; it is operator guidance, not a certification of any kind.
Operator-specific deployment workflows are intentionally **not** in this repository;
`.github/workflows/ci.yml` runs checks only. Configure your own secret manager, deployment
approvals, backups, recovery, domain, and provider agreements.

The delivered example configuration uses local Ollama generation and embedding. Pointing the
model adapter at an external OpenAI-compatible endpoint is an operator choice, and tenant
excerpts would then cross that provider boundary. For regulated workloads review residency,
retention, procurement, threat models, and legal basis. No Vietnamese-law corpus, trained
Dream-RSI policy model, or evaluated fine-tuned checkpoint is bundled.

## Publication gate

This local source export has no private Git history or remote. Before publishing it, a rights
holder and security owner must:

1. Approve distribution rights for each authored component, contribution, translation, logo,
   generated artifact, vendored asset, model, and dataset; preserve third-party notices.
2. Review the entire exported tree and all documentation, fixtures, images, config examples,
   and generated files for customer information, credentials, internal addresses, and private
   agreements. Rotate any exposed secret; never copy the private repository's history, Actions
   secrets, or production `.env` files.
3. Configure a monitored private vulnerability-reporting channel, maintainers/review policy,
   and repository ownership; see [SECURITY.md](../SECURITY.md).
4. Verify from a fresh clone that the sample stack installs, ingests an authorized document,
   enforces tenant isolation, shows citations, and abstains without evidence. The development
   configuration has known defaults and must never be applied to production. Validate production
   exposure, provider egress, and backups separately.

No GitHub repository has been published by these file changes; this checklist does not assert
that legal, security, or operational reviews have passed.
