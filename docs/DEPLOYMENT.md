# Self-hosted installation

[Documentation index](README.md) · [Development](DEVELOPMENT.md) · [Architecture](ARCHITECTURE.md)

This guide is for an operator installing OpenVie on a host they control. It documents the
configuration this repository actually delivers and the checks to run after installing it.

**On this page:** [what is delivered](#1-what-this-release-delivers),
[prerequisites](#2-prerequisites) and [platform notes](#platform-notes),
[configuration](#3-prepare-configuration),
[infrastructure](#4-start-the-infrastructure-and-native-model-service), [applications](#5-install-and-run-the-applications),
[first-run setup](#first-run-web-setup-and-owner-recovery), [verification](#6-verification),
[operations](#7-operating-the-stack), [rollback](#8-migrations-and-rollback),
[edge gateway](#9-edge-gateway-apache-apisix), [gRPC](#10-internal-grpc-transport).

## 1. What this release delivers

| Delivered | Notes |
| --- | --- |
| [`docker-compose.yml`](../docker-compose.yml) | The only Compose file. Starts PostgreSQL, Redis, RabbitMQ, Qdrant, SeaweedFS, the Kuzu graph service, and the [Apache APISIX edge gateway](#9-edge-gateway-apache-apisix), with an optional `reranker` profile |
| Spring API (`api/`) | Runs on the host under `make -C api`; Flyway migrates at startup |
| Python AI/chat service (`rag-chatbot-fastapi/`) | Runs on the host under `make -C rag-chatbot-fastapi`; embeds the document worker by default |
| Web console (`frontend/`) | Built and served with npm |
| [`.env.production.example`](../.env.production.example) | Configuration template for all of the above |

**Not delivered in this release:** TLS termination and domain configuration, a web-application
firewall, malware scanning of uploads, deployment/backup/restore scripts, and any
hosted-operations configuration. Those are operator-owned: terminate TLS in front of the
[edge gateway](#9-edge-gateway-apache-apisix), and choose your own process supervisor, log
pipeline, and backup tooling. `.github/workflows/ci.yml` runs checks only and never deploys.

The shipped Compose file publishes development-style ports on the host. Before a deployment
reachable from other machines, close or firewall every port you do not intend to expose and
keep PostgreSQL, Redis, RabbitMQ, Qdrant, SeaweedFS, Ollama, and the graph service reachable
only from the host or your internal network.

## 2. Prerequisites

| Requirement | Notes |
| --- | --- |
| Docker with the Compose plugin | Linux: Engine plus `docker compose version`. Windows and macOS: Docker Desktop. Required for data services and the Linux/Windows-WSL2 reranker path |
| JDK 21 | `api/` ships Maven wrappers |
| Python 3.11 or 3.12 | As declared in [`pyproject.toml`](../rag-chatbot-fastapi/pyproject.toml). On Windows pass `PYTHON=python` or `PYTHON="py -3.11"`; the `python3.11` command name does not exist there |
| Node.js LTS + npm | For building the web console |
| Make and a POSIX shell | Linux/macOS directly; Windows through WSL2 (or Git Bash with GNU Make) |
| Disk for models | Ollama model weights, the fastembed sparse model, and the optional TEI reranker model |

### Platform notes

Every command in this guide is POSIX shell syntax unless a block is marked PowerShell:

| Topic | Linux | macOS | Windows |
| --- | --- | --- | --- |
| Shell for `make`, `curl`, `sed`, `test` | `bash` or `zsh` | `zsh` | WSL2 (`wsl`) or Git Bash with GNU Make. PowerShell resolves `curl` to `Invoke-WebRequest`, so use `curl.exe` or a WSL2 shell for the health checks in [verification](#6-verification) |
| Data services | Docker Engine plus the Compose plugin; add your user to the `docker` group | Docker Desktop | Docker Desktop with the WSL2 backend |
| Model service | systemd unit created by the Ollama installer | Homebrew service (`launchctl`) | Per-user application started at sign-in; on a headless host run `ollama serve` under a service wrapper such as NSSM |
| Secret generation and file permissions | `openssl rand -hex 32`, `chmod 600` | Same | Git Bash ships `openssl`; restrict `.env.production` with `icacls` or keep it in a directory only your account can read |
| Application supervision | systemd | launchd | WSL2's systemd, or a Windows service wrapper such as NSSM or Task Scheduler |
| Resource checks in [operations](#7-operating-the-stack) | `free -h`, `df -h` | `vm_stat`, `df -h` | Run inside WSL2 |

Continuous integration runs on Linux only
([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)); confirm each step on your own
platform.

## 3. Prepare configuration

Copy the template outside source control and restrict it:

```bash
cp .env.production.example .env.production
chmod 600 .env.production
```

In PowerShell, replace the first line with
`Copy-Item .env.production.example .env.production`. `chmod` inside WSL2 does not change
Windows ACLs: restrict the file with `icacls`, or keep it in a directory only your account can
read.

Replace every example domain, email address, password, token, and key. Generate independent
secrets rather than reusing one value:

```bash
openssl rand -hex 32
```

`openssl` ships with Git for Windows and inside WSL2; in PowerShell call `openssl.exe` from a
Git for Windows installation. Do not substitute a non-cryptographic generator such as
`Get-Random`.

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
  dimension, and Qdrant vector name aligned. On Windows, run Ollama inside the same WSL2
  distro that runs the Python service, or verify the address from that environment first:
  Windows and WSL2 do not share `localhost` in every networking mode.
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

Install Ollama on the host — not in a container — and run it under the operating system's
service manager:

```bash
# Linux: installs the binary and creates/enables the systemd unit
curl -fsSL https://ollama.com/install.sh | sh
systemctl status ollama --no-pager
```

```bash
# macOS
brew install ollama
brew services start ollama
```

```powershell
# Windows PowerShell (per-user install that starts at sign-in)
irm https://ollama.com/install.ps1 | iex
```

The Linux installer cannot start the unit when systemd is disabled (some WSL2 setups), and the
Windows installer starts Ollama with your user session rather than at boot. For a headless
Windows host, run `ollama serve` under a service wrapper such as NSSM using the standalone zip
from Ollama's Windows documentation. Ollama must be listening on `127.0.0.1:11434` before the
host Python process starts.

Pulling installs a model on disk; it does not load that model into memory:

```bash
ollama pull bge-m3
ollama pull hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
ollama cp hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M vylinh
ollama rm hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
ollama list
```

The removal deletes only the long source tag; the `vylinh` alias retains the shared model data.
OpenVie loads both models on demand. A deployed service should warm both before accepting traffic
and pin them in memory until Ollama restarts:

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
| `LLM_MAX_OUTPUT_TOKENS` | `512` | Caps generated tokens per chat answer. Must leave room for the longest enumeration the corpus expects; a low value truncates listed items. |
| `GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS` | `512` | Caps generated tokens per extraction batch. A length-limited multi-chunk batch is subdivided; a still-length-limited single unit is omitted from the concise graph rather than failing the indexed document. |
| `GRAPH_EXTRACTION_MAX_ENTITIES_PER_UNIT` | `2` | Keeps the most central entities per knowledge unit. |
| `GRAPH_EXTRACTION_MAX_RELATIONS_PER_UNIT` | `2` | Keeps the most central grounded relations per knowledge unit. |
| `GRAPH_EXTRACTION_BATCH_SIZE` | `4` | Chunks per extraction request. |

Keep `INGESTION_WORKER_CONCURRENCY` at or below `OLLAMA_NUM_PARALLEL`; higher values only build a
queue inside the model server. Set it to `1` when the model server is serial. Set the Ollama
variables through the platform's service manager so they survive restarts and reach the process
that actually starts Ollama:

```bash
# Linux (systemd): opens a drop-in override
sudo systemctl edit ollama
```

Add this block in the editor that opens, then save:

```ini
[Service]
Environment="OLLAMA_NUM_PARALLEL=4"
Environment="OLLAMA_MAX_LOADED_MODELS=3"
```

```bash
sudo systemctl daemon-reload
sudo systemctl restart ollama
```

```bash
# macOS
launchctl setenv OLLAMA_NUM_PARALLEL 4
launchctl setenv OLLAMA_MAX_LOADED_MODELS 3
brew services restart ollama
```

Windows: quit Ollama from the notification area, add `OLLAMA_NUM_PARALLEL=4` and
`OLLAMA_MAX_LOADED_MODELS=3` as user or system environment variables, then start Ollama again.
When `ollama serve` runs under a service wrapper, set the variables in that service's
environment instead of your interactive session.

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

PowerShell cannot source a dotenv file. Run this section inside WSL2, or load plain `KEY=value`
lines into the current PowerShell session before continuing:

```powershell
Get-Content .\.env.production | ForEach-Object {
  if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
    Set-Item -Path "Env:$($matches[1])" -Value $matches[2].Trim('"', "'")
  }
}
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

Run them under your own supervisor — systemd on Linux, launchd on macOS, WSL2's systemd or a
Windows service wrapper such as NSSM or Task Scheduler on Windows, or containers of your own
construction — so they restart after failure and reboot. Keep their logs off-host if your
retention policy requires it, and never log tokens, credentials, or document text.

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

`free -h` exists on Linux only: use `vm_stat` on macOS, and run these checks inside WSL2 on
Windows.

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

## 9. Edge gateway (Apache APISIX)

`docker compose up -d --wait` also starts **Apache APISIX** as the single HTTP entry point on
`127.0.0.1:${APISIX_PORT:-8088}`. Configuration lives in
[`apisix/config.yaml`](../apisix/config.yaml) and [`apisix/routes.yaml`](../apisix/routes.yaml);
APISIX runs in standalone YAML mode (`APISIX_STAND_ALONE=true`), so there is no etcd, no control
plane and no Admin API to secure. The port `Caddyfile.dev` used for its one-line development
shim is the same one, so run either Caddy or APISIX on it, not both.

| Route on the gateway | Upstream | Edge policy |
| --- | --- | --- |
| `/apisix/status` | answered by the gateway | health probe, no upstream |
| `/api/v1/auth/*` | Spring `host.docker.internal:8080` | `limit-req`: 5 r/s, burst 10 → HTTP 429 |
| `/api/v1/*` | Spring `host.docker.internal:8080` | `limit-req`: 50 r/s, burst 100 → HTTP 429 |
| `/*` | web client `host.docker.internal:3000` | — |

Deliberately **not** routed: the AI HTTP service (`:18000`), the internal gRPC interface
(`:50051`), PostgreSQL, Redis, RabbitMQ, Qdrant, SeaweedFS, and the graph service. They stay
reachable only from the host and the Compose network.

Verify the edge:

```bash
docker compose ps apisix                     # healthy
curl -fsS http://127.0.0.1:8088/apisix/status # {"status": "pass"}
for i in $(seq 1 30); do curl -s -o /dev/null -w '%{http_code}\n' \
  http://127.0.0.1:8088/api/v1/auth/registration-status; done | sort | uniq -c  # expect 429
```

To serve the console and the API from one origin, run the client with a relative API base:

```bash
NEXT_PUBLIC_API_BASE_URL=/api/v1 npm --prefix frontend run dev  # open http://localhost:8088
```

`NEXT_PUBLIC_*` values are inlined at build time, so a production bundle needs the same value
during `npm run build`. One origin means no CORS preflight and the session cookie is set on the
gateway origin.

**Client IP behind the gateway.** Spring only honours `X-Forwarded-For` from a trusted proxy;
`app.security.trusted-proxy-cidrs` defaults to `127.0.0.1/32,::1/128`. When traffic arrives from
the container bridge, set `TRUSTED_PROXY_CIDRS` to your Docker network range (for example
`172.16.0.0/12`), otherwise audit records and the public-route rate limiter key on the gateway
address instead of the real client.

**Still operator-owned:** TLS/HTTPS, IP filtering, and web-application firewalling or malware
scanning of uploads are not configured here. Put your own TLS terminator in front of port 8088.

## 10. Internal gRPC transport

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

## 11. Out of scope

The following are intentionally operator-owned and are not claims this repository makes:
TLS/HTTPS and domain configuration, IP filtering and web-application firewalling, malware
scanning of uploads, parser sandboxing, log aggregation and retention, monitoring and alerting,
backup scheduling and restore drills, and any availability or latency guarantee. The
[edge gateway](#9-edge-gateway-apache-apisix) applies per-IP `limit-req` at the edge; it does
not terminate TLS. Measure your own deployment and keep the evidence with your release records.
