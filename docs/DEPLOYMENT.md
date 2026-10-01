# Self-hosted installation

[Documentation index](README.md) · [Development](DEVELOPMENT.md) · [Architecture](ARCHITECTURE.md)

This guide is for an operator installing OpenVie on a host they control. It documents the
configuration this repository actually delivers and the checks to run after installing it.

**On this page:** [what is delivered](#1-what-this-release-delivers),
[prerequisites](#2-prerequisites), [configuration](#3-prepare-configuration),
[infrastructure](#4-start-the-infrastructure), [applications](#5-install-and-run-the-applications),
[bootstrap](#operator-bootstrap), [verification](#6-verification),
[operations](#7-operating-the-stack), [rollback](#8-migrations-and-rollback),
[gRPC](#9-internal-grpc-transport).

## 1. What this release delivers

| Delivered | Notes |
| --- | --- |
| [`docker-compose.yml`](../docker-compose.yml) | The only Compose file. Starts PostgreSQL, Redis, RabbitMQ, Qdrant, Ollama, SeaweedFS, and the Kuzu graph service, with an optional `reranker` profile |
| Spring API (`api/`) | Runs on the host under `make -C api`; Flyway migrates at startup |
| Python AI/chat service (`rag-chatbot-fastapi/`) | Runs on the host under `make -C rag-chatbot-fastapi`; embeds the document worker by default |
| Web console (`frontend/`) | Built and served with npm |
| [`.env.production.example`](../.env.production.example) | Configuration template for all of the above |

**Not delivered in this release:** production ingress (TLS termination, reverse proxy, edge
rate limiting), deployment/backup/restore scripts, and any hosted-operations configuration.
Those are operator-owned: put your own proxy in front of the console and API, and choose your
own process supervisor, log pipeline, and backup tooling. `.github/workflows/ci.yml` runs
checks only and never deploys.

The shipped Compose file publishes development-style ports on the host. Before a deployment
reachable from other machines, close or firewall every port you do not intend to expose and
keep PostgreSQL, Redis, RabbitMQ, Qdrant, SeaweedFS, Ollama, and the graph service reachable
only from the host or your internal network.

## 2. Prerequisites

| Requirement | Notes |
| --- | --- |
| Docker with the Compose plugin | Capacity for the data services plus model weights |
| JDK 21 | `api/` ships Maven wrappers |
| Python 3.11 or 3.12 | As declared in [`pyproject.toml`](../rag-chatbot-fastapi/pyproject.toml) |
| Node.js LTS + npm | For building the web console |
| Make and a POSIX shell | All documented commands assume them |
| Disk for models | Ollama model weights, the fastembed sparse model, and the optional reranker model |

## 3. Prepare configuration

Copy the template outside source control and restrict it:

```bash
cp .env.production.example .env.production
chmod 600 .env.production
```

Replace every example domain, email address, password, token, and key. Generate independent
secrets rather than reusing one value:

```bash
openssl rand -hex 32
```

At minimum review:

- **Public URLs:** `CORS_ORIGINS`, `VERIFICATION_LINK`, `LOGIN_2FA_LINK`, `INVITATION_LINK`, and
  the `NEXT_PUBLIC_*` block. Client-facing URLs must match the origin your users actually reach.
- **Secrets:** `TOKEN_KEY`, `GRAPH_INTERNAL_TOKEN`, `POSTGRES_PASSWORD`, `RABBITMQ_DEFAULT_PASS`.
  Keep them consistent with `RABBITMQ_URL`, which embeds the broker credentials.
- **Storage:** `SEAWEEDFS_*` access/secret keys when the S3 endpoint is reachable by others.
- **Email:** `MAIL_PROVIDER` plus the matching credential (`SENDGRID_API_KEY`, `BREVO_API_KEY`,
  or `MAIL_HOST`/`MAIL_PORT`/`MAIL_USERNAME`/`MAIL_PASSWORD`) and `FROM_EMAIL`. Startup fails
  with the missing variable name when the selected provider is not configured; login 2FA and
  invitations depend on it.
- **Model path:** the delivered defaults are local Ollama (`LLM_PROVIDER=ollama`) with
  `LLM_MODEL_ID` matching a model you pull in the next step. Model endpoints
  (`LLM_BASE_URL`, `TEXT_EMBEDDING_BASE_URL`, `RERANKER_URL`) intentionally live in
  `docker-compose.yml` (container) and `rag-chatbot-fastapi/.env` (host); set them there.
- **2FA:** `LOGIN_2FA_BYPASS_EMAILS` must be empty for normal operation.
- **Worker mode:** `WORKER_MODE=embedded` runs the document consumer inside the Python process.
  If you start a separate consumer, set `WORKER_MODE=disabled` for the main process and never
  run both consumers at once.
- **Caches:** all cache flags default to off; see [caches](ARCHITECTURE.md#caches) before
  enabling one.

Copy the `NEXT_PUBLIC_*` values into `frontend/.env.local` before building the console — they
are compiled into the bundle and cannot change afterwards.

Validate the rendered Compose model before starting anything:

```bash
docker compose --env-file .env.production config --quiet
docker compose --env-file .env.production --profile reranker config --quiet
```

Both commands must exit successfully. Fix any reported problem before continuing.

## 4. Start the infrastructure

```bash
docker compose --env-file .env.production up -d --wait
```

Then pull the models referenced by your configuration (names must match `LLM_MODEL_ID` and
`TEXT_EMBEDDING_MODEL_ID`):

```bash
docker compose --env-file .env.production exec ollama ollama pull embeddinggemma
docker compose --env-file .env.production exec ollama ollama pull gemma4:12b
```

A running Ollama container does not imply the models are downloaded. Start the optional
reranker only when you configured it:

```bash
docker compose --env-file .env.production --profile reranker up -d reranker-service
```

## 5. Install and run the applications

Expose the production values to your shell for this section:

```bash
set -a
. ./.env.production
set +a
```

Python service (this also installs its dependencies):

```bash
make -C rag-chatbot-fastapi setup PYTHON=python3.11
make -C rag-chatbot-fastapi run PYTHON=python3.11
```

Spring API (pass the production file so Make does not load the development `api/.env`):

```bash
make -C api migrate ENV_FILE=../.env.production
make -C api run-prod ENV_FILE=../.env.production
```

Web console:

```bash
npm --prefix frontend ci
npm --prefix frontend run build
npm --prefix frontend run start
```

Run them under your own supervisor (systemd, launchd, containers of your own construction) so
they restart after failure and reboot. Keep their logs off-host if your retention policy
requires it, and never log tokens, credentials, or document text.

The `prod` Spring profile runs Flyway with `validate-on-migrate` and Hibernate
`ddl-auto=validate`; a schema/code mismatch stops startup instead of silently altering the
schema.

## Operator bootstrap

A fresh install has no tenant or user. Create the first one explicitly:

```bash
BOOTSTRAP_TENANT_NAME="Example Organization" \
BOOTSTRAP_ADMIN_EMAIL="admin@example.com" \
BOOTSTRAP_ADMIN_PASSWORD="a-long-unique-passphrase" \
make -C api bootstrap-tenant ENV_FILE=../.env.production
```

The command packages the API and runs it once in `bootstrap-tenant` mode. It refuses to run
when any tenant or user already exists, refuses a missing value, refuses the known development
password (`Cacanode@123`), and requires at least 12 characters, so it can never silently
overwrite an existing installation. The environment must include working mail configuration
because the command boots the same application context as a normal start.

Never reuse the development seed account or its password outside a local stack.

## 6. Verification

Infrastructure and application health:

```bash
docker compose --env-file .env.production ps
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:18000/health/live
curl --fail http://localhost:18000/health/ready
curl --fail http://localhost:8010/health/ready
```

A ready response reports model configuration and bounded diagnostics; it is not proof that
generation works. Exercise the real paths through the console:

1. Sign in with the bootstrapped administrator and complete login 2FA using the configured mail.
2. Upload a small supported document and observe its status reach `COMPLETED`.
3. Ask a question that document answers and verify the citations resolve to it.
4. Ask something the knowledge base cannot answer and confirm the answer abstains.
5. Reload the page and confirm the conversation persists.

Repeat the checks after every configuration, model, or index change, and record the model,
embedding, and index versions alongside the result.

## 7. Operating the stack

### Logs and resources

```bash
docker compose --env-file .env.production logs -f graph-service ollama qdrant
docker stats --no-stream
free -h
df -h
```

Application logs come from the processes (or supervisors) you started in section 5. If memory
is constrained, stop the optional reranker first, then pause new uploads, and inspect queue
depth before touching the graph service.

### Data and backups

Named Compose volumes hold PostgreSQL, Redis, RabbitMQ, Qdrant, Ollama, Kuzu, and SeaweedFS
data; the Python service writes derived artifacts and the Kuzu file under its working
directory. **This repository ships no backup or restore scripts.** Provide your own scheduled,
off-host backups covering:

- PostgreSQL (a consistent dump),
- SeaweedFS volumes (uploaded source objects),
- Qdrant and Kuzu (derived indexes; they can be rebuilt by re-ingesting documents, which is
  usually cheaper than restoring them),
- Redis (AOF/RDB if you rely on ingestion checkpoints),
- `.env.production` and any certificates, stored encrypted.

Prove a restore in an isolated environment before relying on it; a backup that has never been
restored is not a recovery capability.

Never use `docker compose down -v` as a routine stop: it deletes every volume above.

### Upgrades

Deploy a reviewed revision, start the data services first, then the Python service, then the
Spring API, then rebuild the console. Keep migrations backward compatible with the previous
release (see the next section), and keep the previous revision available to roll back to.

## 8. Migrations and rollback

Migrations run when the Spring API starts; there is no separate migration job. Database
rollback is not automatic: every migration must be backward compatible with the immediately
previous application release. Use additive changes first, deploy code that tolerates old and
new schemas, and drop old columns only in a later release.

Rollback therefore means running the previous application code against the migrated schema.
If a schema change is incompatible with the previous release, restore from a tested backup or
deploy a forward fix; switching code alone cannot undo a schema.

### Graph rollout and document reindexing

Deploy the graph service before the API and worker roles. When graph extraction or indexing
behavior changes, re-ingest existing `COMPLETED` documents to rebuild derived relations rather
than assuming a code rollout rewrites stored graph data. The one-shot Spring
[DocumentReindexCommand](../api/src/main/java/com/cacanode/api/document/service/DocumentReindexCommand.java)
is gated by `app.maintenance.reindex.enabled=true`. It batches `COMPLETED` documents
(default 100, bounded to 1–1000) and enqueues ingestion events; it does not wait for successful
ingestion. Enable it only for the intended maintenance start, watch ingestion outcomes, and
remove the flag afterwards so later restarts do not enqueue again. It is not a schema
migration tool for the graph store.

## 9. Internal gRPC transport

The `prod` Spring profile defaults to certificate-authenticated TLS with
`AI_GRPC_PLAINTEXT=false` and certificate paths under `AI_GRPC_CA_CERTIFICATE`,
`AI_GRPC_CLIENT_CERTIFICATE`, and `AI_GRPC_CLIENT_KEY`. Provide CA-signed files readable by
the API process, or explicitly set `AI_GRPC_PLAINTEXT=true` when both processes run on a host
you alone control. The default `AI_GRPC_TARGET` (`ai-api:50051`) and
`AI_GRPC_AUTHORITY_OVERRIDE` (`ai-api`) assume container DNS names; use `localhost:50051` for
host-run processes.

For certificate rotation, stage the replacement files beside the current set, validate both
chains, atomically replace the mounted files, restart the Python service and then the Spring
API, and keep the previous CA trusted until every process has restarted.

## 10. Out of scope

The following are intentionally operator-owned and are not claims this repository makes:
TLS/HTTPS and domain configuration, edge rate limiting and IP filtering, malware scanning of
uploads, parser sandboxing, log aggregation and retention, monitoring and alerting, backup
scheduling and restore drills, and any availability or latency guarantee. Measure your own
deployment and keep the evidence with your release records.
