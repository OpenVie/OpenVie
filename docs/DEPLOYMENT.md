# Self-hosted installation

[Documentation index](README.md) · [Development](DEVELOPMENT.md) · [Architecture](ARCHITECTURE.md)

This guide is for an operator installing OpenVie on a host they control. It documents the
configuration this repository actually delivers and the checks to run after installing it.

**On this page:** [what is delivered](#1-what-this-release-delivers),
[prerequisites](#2-prerequisites), [configuration](#3-prepare-configuration),
[infrastructure](#4-start-the-infrastructure-and-native-model-service), [applications](#5-install-and-run-the-applications),
[first-run setup](#first-run-web-setup-and-owner-recovery), [verification](#6-verification),
[operations](#7-operating-the-stack), [rollback](#8-migrations-and-rollback),
[gRPC](#9-internal-grpc-transport).

## 1. What this release delivers

| Delivered | Notes |
| --- | --- |
| [`docker-compose.yml`](../docker-compose.yml) | The only Compose file. Starts PostgreSQL, Redis, RabbitMQ, Qdrant, SeaweedFS, and the Kuzu graph service, with an optional `reranker` profile |
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
| Docker with the Compose plugin | Required for data services and the Linux/Windows-WSL2 reranker path |
| JDK 21 | `api/` ships Maven wrappers |
| Python 3.11 or 3.12 | As declared in [`pyproject.toml`](../rag-chatbot-fastapi/pyproject.toml) |
| Node.js LTS + npm | For building the web console |
| Make and a POSIX shell | Linux/macOS directly; Windows through WSL2 |
| Disk for models | Ollama model weights, the fastembed sparse model, and the optional TEI reranker model |

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

- **Public URLs:** `CORS_ORIGINS`, `INVITATION_LINK`, and the `NEXT_PUBLIC_*` block.
  Client-facing URLs must match the origin your users actually reach.
- **Secrets:** `TOKEN_KEY`, `NOTIFICATION_ENC_KEY`, `GRAPH_INTERNAL_TOKEN`, `POSTGRES_PASSWORD`,
  `RABBITMQ_DEFAULT_PASS`. Keep them consistent with `RABBITMQ_URL`, which embeds the broker credentials.
  `NOTIFICATION_ENC_KEY` (32 random bytes, base64: `openssl rand -base64 32`) is required if you store
  notification channels in the database via the settings console.
- **Storage:** `SEAWEEDFS_*` access/secret keys when the S3 endpoint is reachable by others.
- **Email (optional):** `MAIL_PROVIDER` defaults to `none`. The application boots and operates
  fully without email: chat, ingestion, and search do not require it. If you want outbound invitations
  immediately, configure `MAIL_PROVIDER` (`smtp`, `sendgrid`, or `brevo`), credentials, and `FROM_EMAIL`
  as the environment bootstrap default, or configure channels later in the web console under `/settings`.
- **Model path:** the delivered defaults use native Ollama (`LLM_PROVIDER=ollama`) with
  Arcee-VyLinh generation and BGE-M3 embeddings. The Python process reaches Ollama through
  localhost; keep `LLM_BASE_URL`, `TEXT_EMBEDDING_BASE_URL`, model identifiers, embedding
  dimension, and Qdrant vector name aligned.
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

## 4. Start the infrastructure and native model service

```bash
docker compose --env-file .env.production up -d --wait
```

Install Ollama on the host and run it under the operating system's service manager. Pulling
installs a model on disk; it does not load that model into memory:

```bash
ollama pull bge-m3
ollama pull hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
ollama cp hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M vylinh
ollama rm hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
ollama list
```

The removal deletes only the long source tag; the `vylinh` alias retains the shared model data.
The Ollama server must listen on `127.0.0.1:11434` before the host Python process starts. OpenVie
loads both models on demand. A deployed service should warm both before accepting traffic and pin
them in memory until Ollama restarts:

```bash
curl --fail --silent --output /dev/null http://127.0.0.1:11434/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"model":"vylinh","keep_alive":-1}'
curl --fail --silent --output /dev/null http://127.0.0.1:11434/api/embed \
  -H 'Content-Type: application/json' \
  -d '{"model":"bge-m3","input":["OpenVie warmup"],"keep_alive":-1}'
ollama ps
```

`ollama list` reports models installed on disk; `ollama ps` reports models currently loaded in
memory. `keep_alive: -1` prevents idle eviction. Run the warm-up from the process supervisor after
every Ollama restart so the service is ready before it receives traffic. An operator may choose a
bounded duration only when memory pressure is more important than avoiding model reload latency.

### Model concurrency and ingestion throughput

Ollama defaults to a single request slot. With that default, one long graph-extraction request
blocks chat, embeddings, and all other ingestion jobs, and document latency grows without bound as
a queue builds. Size the model server and the worker together:

| Setting | Default | Effect |
| --- | --- | --- |
| `OLLAMA_NUM_PARALLEL` | `4` on this host | Parallel model slots on the Ollama server. Memory grows per slot. |
| `OLLAMA_MAX_LOADED_MODELS` | `3` on this host | Keeps both `vylinh` and `bge-m3` resident without unloading each other. |
| `INGESTION_WORKER_CONCURRENCY` | `4` | Maximum documents a worker processes concurrently. Also the RabbitMQ prefetch count. |
| `GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS` | `4096` | Caps generated tokens per extraction batch; dense tables and multi-chunk batches can generate up to 2,000–3,000 tokens of structured JSON. |
| `GRAPH_EXTRACTION_BATCH_SIZE` | `4` | Chunks per extraction request. |

Keep `INGESTION_WORKER_CONCURRENCY` at or below `OLLAMA_NUM_PARALLEL`; higher values only build a
queue inside the model server. Set it to `1` when the model server is serial. On Apple Silicon,
set the Ollama variables through the service manager so they survive restarts:

```bash
launchctl setenv OLLAMA_NUM_PARALLEL 4
launchctl setenv OLLAMA_MAX_LOADED_MODELS 3
brew services restart ollama
```

Confirm the change on a running server by checking that concurrent requests complete together
rather than strictly one after another, and re-run the warm-up commands so both models reload into
the new slots.

### Optional reranker by platform

Reranking is opt-in. Set `RERANKER_ENABLED=true` only after a compatible TEI endpoint is healthy.
The delivered default is the 306M-parameter
[`Alibaba-NLP/gte-multilingual-reranker-base`](https://huggingface.co/Alibaba-NLP/gte-multilingual-reranker-base),
which explicitly supports Vietnamese and more than 70 languages. The model server and Python
application must use the same `RERANKER_MODEL_ID`.

Linux x86-64 and Windows Docker Desktop with its WSL2 backend use the shipped CPU profile:

```bash
docker compose --env-file .env.production --profile reranker up -d reranker-service
```

Linux ARM64 uses the official ARM image:

```bash
RERANKER_IMAGE=ghcr.io/huggingface/text-embeddings-inference:cpu-arm64-1.9 \
  docker compose --env-file .env.production --profile reranker up -d reranker-service
```

For an NVIDIA deployment, select the TEI 1.9 image matching the GPU architecture and add GPU
access in an operator-owned Compose override. For example, RTX 40 series uses:

```yaml
services:
  reranker-service:
    image: ghcr.io/huggingface/text-embeddings-inference:89-1.9
    gpus: all
```

Pass that override with `-f` after the repository Compose file. Windows GPU deployment uses the
same Linux container through WSL2 GPU passthrough. Do not select a CUDA image without granting
the container GPU access.

Apple Silicon should use native TEI with Metal rather than a Linux container:

```bash
brew install text-embeddings-inference
text-embeddings-router \
  --model-id Alibaba-NLP/gte-multilingual-reranker-base \
  --hostname 127.0.0.1 \
  --port 8082
```

The reranker has no public authentication boundary. Keep port 8082 on loopback or a private
service network. OpenVie's reranker client is fail-open: endpoint errors retain the fused
retrieval order rather than failing answer generation.

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

## First-run web setup and owner recovery

A fresh install has no organization or users. Open the web console in your browser
(e.g. `http://localhost:3000` or your configured domain).

1. The web application checks `GET /api/v1/auth/registration-status`, detects an unclaimed installation,
   and redirects to `/setup`.
2. Enter the organization name, your full name, owner email, and a password (minimum 12 characters).
   Optionally enable self-registration if you want members to sign up without an explicit invitation.
3. Submitting claims the installation, creates the organization, the owner account (`ORG_OWNER`), and
   the default workspace.

Setup is one-time and permanent: the `/setup` endpoint is disabled and returns 404 once any account exists.

### Offline owner password reset

If an organization owner password is ever lost in a headless environment, reset it using the
`recover-owner` command without direct SQL intervention:

```bash
make -C api recover-owner ENV_FILE=../.env.production RECOVER_ARGS="--recover-owner-email=owner@example.com --recover-owner-password=a-long-unique-passphrase"
```

The command verifies that the email belongs to an active `ORG_OWNER` account, enforces password strength
rules, and updates the credential. It refuses to create accounts or escalate privileges.
## 6. Verification

Infrastructure and application health:

```bash
docker compose --env-file .env.production ps
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:18000/health/live
curl --fail http://localhost:18000/health/ready
curl --fail http://localhost:8010/health/ready
# When RERANKER_ENABLED=true:
curl --fail http://localhost:8082/health
curl --fail http://localhost:8082/rerank \
  -H 'Content-Type: application/json' \
  -d '{"query":"Nhân viên được làm việc từ xa bao nhiêu ngày?","texts":["Nhân viên chính thức được làm việc từ xa tối đa 2 ngày mỗi tuần.","Thời gian thử việc kéo dài 60 ngày."],"truncate":true}'
```

A ready response reports model configuration and bounded diagnostics; it is not proof that
generation works. Exercise the real paths through the console:

1. Complete setup at `/setup` (or sign in with the owner account; authentication is password-only).
2. Upload a small supported document and observe its status reach `COMPLETED`.
3. Ask a question that document answers and verify the citations resolve to it.
4. Ask something the knowledge base cannot answer and confirm the answer abstains.
5. Reload the page and confirm the conversation persists.

Repeat the checks after every configuration, model, or index change, and record the model,
embedding, and index versions alongside the result.

## 7. Operating the stack

### Logs and resources

```bash
docker compose --env-file .env.production logs -f graph-service qdrant
docker stats --no-stream
free -h
df -h
```

Application logs come from the processes (or supervisors) started in sections 4 and 5, including
native Ollama and, on Apple Silicon, native TEI. Container deployments can inspect the optional
reranker with `docker compose logs -f reranker-service`. If memory is constrained, stop the
optional reranker first, then pause new uploads and inspect queue depth before touching the graph
service.

### Data and backups

Named Compose volumes hold PostgreSQL, Redis, RabbitMQ, Qdrant, Kuzu, and SeaweedFS data; native
Ollama stores models in its host data directory. The Python service writes derived artifacts and the Kuzu file under its working
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
