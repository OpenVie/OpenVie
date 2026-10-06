# Local development

[Documentation index](README.md) · [Project overview](../README.md) · [Deployment](DEPLOYMENT.md)

This guide runs infrastructure in Docker and the Java, Python, and web applications on the host.
It is not an installation recipe for a reachable deployment; see [DEPLOYMENT.md](DEPLOYMENT.md)
for self-hosted installation guidance. Commands below start from the repository root unless a
different working directory is shown.

## Prerequisites

| Tool | Requirement |
| --- | --- |
| Git, Make, shell | Commands below use a POSIX shell. Linux and macOS run them natively; Windows runs them in WSL2 or another POSIX shell with GNU Make — see [platform notes](#platform-notes) |
| Docker | Linux: Engine with the Compose plugin (`docker compose version` must succeed) and your user in the `docker` group. Windows and macOS: Docker Desktop with the Compose plugin, WSL2 backend enabled on Windows. All platforms need room for databases and model weights |
| Java | JDK 21; the API includes Maven wrappers |
| Python | 3.11 or 3.12, as declared in [`pyproject.toml`](../rag-chatbot-fastapi/pyproject.toml) |
| Node.js and npm | Supported LTS; Node 22.13 or newer on the 22.x line works with the client's declared requirements |
| TEI (optional) | Compose on Linux/Windows WSL2; native Homebrew binary on Apple Silicon |

Install the toolchain from your distribution's packages, a version manager (SDKMAN!, pyenv,
nvm), or the platform installer (Homebrew, winget). The examples use `PYTHON=python3.11`.
Substitute `python3.12` consistently if that is your installed interpreter; on Windows that
command name does not exist, so pass `PYTHON=python` or `PYTHON="py -3.11"` instead. Model
downloads and first container builds require network access.

### Platform notes

Continuous integration runs the commands below on Linux
([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)); macOS and Windows reproduce the
same steps but are not covered by CI, so confirm them on your own machine.

| Topic | Linux | macOS | Windows |
| --- | --- | --- | --- |
| Shell for `make`, `curl`, `sed`, `test` | `bash` or `zsh` | `zsh` | WSL2 (`wsl`) or Git Bash with GNU Make. PowerShell resolves `curl` to `Invoke-WebRequest` and cannot expand Make recipe syntax such as `VAR=value command` |
| Docker | Engine plus the Compose plugin | Docker Desktop | Docker Desktop with the WSL2 backend |
| Checkout location | Any path | Any path | Prefer the WSL filesystem for I/O and file watching; if the checkout stays on a Windows drive, add that path to Docker Desktop's file sharing |
| Ollama | systemd unit created by the installer | Homebrew service | Per-user Windows application started at sign-in |
| Secrets and file permissions | `openssl`, `chmod` | `openssl`, `chmod` | Git Bash ships `openssl`; `chmod` inside WSL2 does not change Windows ACLs |

Everything not listed here is identical on all three platforms: the same `.env` files, ports,
Make targets, and health checks. When a command fails only on Windows, re-run it in WSL2 before
changing configuration.

## Local setup

### Create configuration without overwriting it

```bash
test -f .env || cp .env.example .env
test -f api/.env || cp api/.env.example api/.env
test -f rag-chatbot-fastapi/.env || cp rag-chatbot-fastapi/.env.example rag-chatbot-fastapi/.env
test -f frontend/.env.local || cp frontend/.env.example frontend/.env.local
```

Review existing files rather than assuming they match the examples. Root `.env` configures
Compose interpolation; service-level files use host-accessible URLs. In particular, `postgres`,
`redis`, and `rabbitmq` are Docker DNS names, not host application addresses. Keep credentials and
published ports aligned between the files. Never commit local secrets.

OpenVie branding does not yet change identifiers such as the `cacanode` database/bucket or
`com.cacanode` Java packages. Keep documented commands aligned with those actual identifiers
until their implementation changes.

### Install dependencies

```bash
make -C api deps
make -C rag-chatbot-fastapi setup PYTHON=python3.11
npm --prefix frontend ci
```

Python `setup` creates `.venv` and installs runtime/development dependencies. After moving the
checkout, recreate a virtual environment if its installed entry points reference the old path;
do not copy a virtual environment between machines.

### Start infrastructure

```bash
make -C rag-chatbot-fastapi dev-infra
docker compose -f docker-compose.yml ps
```

This starts PostgreSQL, Redis, RabbitMQ, Qdrant, SeaweedFS, and the Kuzu graph service. It does
**not** start native Ollama, Spring, the host Python inference app, or Next.js. The optional
reranker profile is not part of this default target.

Default host endpoints come from [`docker-compose.yml`](../docker-compose.yml):

| Service | Host endpoint |
| --- | --- |
| PostgreSQL | `localhost:15432` |
| Redis | `localhost:16379` |
| RabbitMQ AMQP / management UI | `localhost:15673` / `http://localhost:25672` |
| Qdrant HTTP | `http://localhost:16333` |
| Native Ollama | `http://localhost:11434` |
| Kuzu graph HTTP | `http://localhost:8010` |
| SeaweedFS S3 / filer | `http://localhost:18333` / `http://localhost:18888` |

Check `.env` for port overrides. Do not start a second host graph process on port 8010 while
the graph container owns that port.

### Prepare models

Run Ollama on the host rather than inside Docker, so generation uses the platform's own GPU
stack instead of CPU-only container virtualization. Install it once for your platform:

```bash
# Linux: installs the binary and creates/enables the systemd unit
curl -fsSL https://ollama.com/install.sh | sh
systemctl status ollama --no-pager
```

```bash
# macOS: Apple Silicon keeps generation on Metal
brew install ollama
brew services start ollama
```

```powershell
# Windows PowerShell: per-user install that starts at sign-in
irm https://ollama.com/install.ps1 | iex
```

If systemd is disabled in your WSL2 distro, the installer cannot start the unit; run
`ollama serve` yourself or enable systemd first. The model commands are identical on every
platform:

```bash
ollama pull bge-m3
ollama pull hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
ollama cp hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M vylinh
ollama rm hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
ollama list
```

The removal drops only the long source tag; `vylinh` retains the shared model data. Pulling
installs models but does not load them. OpenVie loads them on demand, or developers can warm and
pin both for the current Ollama server lifetime:

```bash
curl --fail --silent --output /dev/null http://127.0.0.1:11434/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"model":"vylinh","keep_alive":-1}'
curl --fail --silent --output /dev/null http://127.0.0.1:11434/api/embed \
  -H 'Content-Type: application/json' \
  -d '{"model":"bge-m3","input":["OpenVie warmup"],"keep_alive":-1}'
ollama ps
```

`ollama list` shows installed models; `ollama ps` shows models loaded in memory. The
`keep_alive: -1` requests prevent idle eviction; use `keep_alive: 0` to unload deliberately.
The `vylinh` alias, `bge-m3` identifier, and 1,024-dimensional embedding setting match the
checked-in configuration. Keep the embedding model, dimension, and Qdrant vector name consistent
with indexed data; changing them requires recreating the collection and re-ingesting documents.

On Windows, either install Ollama inside the same WSL2 distro that runs the applications
(`localhost` then works unchanged), or run the native Windows application and reach it from
WSL2. `localhost` is not shared between Windows and WSL2 in every networking mode, so verify
from inside WSL2 before starting the stack:

```bash
# Ollama installed inside WSL2:
curl http://127.0.0.1:11434/api/tags
# Ollama running natively on Windows, reached from WSL2 (NAT networking):
curl http://$(awk '/^nameserver/ {print $2; exit}' /etc/resolv.conf):11434/api/tags
```

Point `LLM_BASE_URL` and `TEXT_EMBEDDING_BASE_URL` at whichever address answers; on Windows 11
with mirrored networking the host is reachable as `localhost`.

#### Enable Ollama request parallelism

By default Ollama serves one request at a time, so a long graph-extraction call blocks chat,
embeddings, and every other ingestion job behind it. Allow parallel slots before benchmarking
ingestion or chat latency. Set the variables through the platform's own mechanism so they
survive restarts — a running server only sees variables that existed when it started.

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
`OLLAMA_MAX_LOADED_MODELS=3` under *Edit environment variables for your account*, then start
Ollama again from the Start menu.

Then re-run the warm-up commands above. `ollama ps` should list both models, and the reported
memory grows because each parallel slot holds its own KV cache. Systems without spare memory
should lower `OLLAMA_CONTEXT_LENGTH` instead of leaving the default context divided across slots.

Ingestion throughput itself is controlled separately:

```dotenv
INGESTION_WORKER_CONCURRENCY=4
```

That setting is the worker's prefetch count, meaning at most that many documents are processed
concurrently. Raising it only helps when the model server can actually serve parallel requests, so
keep it at or below `OLLAMA_NUM_PARALLEL`, or at `1` when the model server is serial.
`GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS` (default `512`) caps extraction output per batch. A
length-limited multi-chunk batch is subdivided; a single unit that still reaches the cap is omitted
from the concise graph rather than failing the document.

The default retains at most two entities and two relations per knowledge unit, so a four-chunk
batch stays within the output budget. Increase the per-unit limits only with a measured timeout
budget.


`make dev` supplies these values through `DEV_GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS` and
`DEV_LLM_MAX_OUTPUT_TOKENS`; override a development variable only after measuring it against the
configured timeout. `LLM_MAX_OUTPUT_TOKENS` (default `512`) is the answer budget, not the extraction
budget: enumerations such as a seven-row reference table are truncated when it is too low.


### Migrate the database

```bash
make -C api migrate
```

`migrate` uses `POSTGRES_URL`, `POSTGRES_USER`, and `POSTGRES_PASSWORD` from `api/.env`.
The development Spring profile also runs Flyway at startup and validates the schema.

The first account is created through the browser: once the applications are started below,
visiting `http://localhost:3000` detects the unclaimed installation and redirects to `/setup`.
Completing that form creates the organization, the owner account (`ORG_OWNER`), and the
default workspace. Subsequent logins use password-only authentication (`POST /api/v1/auth/login`).
If an owner password is ever lost in a headless environment, reset it with:

```bash
make -C api recover-owner RECOVER_ARGS="--recover-owner-email=owner@example.com --recover-owner-password=a-long-password"
```

### Start the applications

Run each command in a separate terminal:

```bash
# Spring business API, port 8080
make -C api dev
```

```bash
# Python inference HTTP, port 18000; gRPC, port 50051
make -C rag-chatbot-fastapi dev PYTHON=python3.11
```

```bash
# Next.js web client, port 3000
npm --prefix frontend run dev
```

Python `make dev` starts the host application with reload and the configured embedded worker
lifecycles; it does not start containers. Its Makefile overrides the graph URL, Qdrant collection,
and local reranker settings with `DEV_*` variables. The HTTP port is controlled by the Make
variable `APP_PORT` (default 18000), independently of Spring's `SERVER_PORT`.

### First-run checks

```bash
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:18000/health/live
curl --fail http://localhost:18000/health/ready
curl --fail http://localhost:8010/health/ready
```

Spring health includes its configured dependency checks. Python readiness reports model
configuration plus bounded worker/connectivity diagnostics; a ready HTTP response is not proof
that generation succeeds or that every diagnostic is healthy. Inspect the body, then exercise
an actual workflow:

1. Open `http://localhost:3000` and complete setup (or sign in with your account).
2. Upload a small supported text document to the workspace knowledge base.
3. Observe its status reach `COMPLETED`; inspect a reported failure rather than assuming the
   upload response means indexing has finished.
4. Ask a question answered by that document and verify the answer's citations.
5. Reload the client and confirm the conversation persists.

No TLS termination is part of this stack. The client normally talks to Spring on
`localhost:8080` directly; the Compose stack also starts an
[Apache APISIX edge gateway](DEPLOYMENT.md#9-edge-gateway-apache-apisix) on
`http://localhost:8088` that fronts both the client and the API:

```bash
docker compose up -d apisix
curl -fsS http://127.0.0.1:8088/apisix/status
NEXT_PUBLIC_API_BASE_URL=/api/v1 npm --prefix frontend run dev  # then open http://localhost:8088
```

See [deployment](DEPLOYMENT.md) for installing the stack on a host you operate.

### Stop without deleting data

Stop host applications with Ctrl-C, then stop their infrastructure:

```bash
make -C rag-chatbot-fastapi dev-down
```

Avoid `docker compose down -v`, `make -C api db-reset`, and `make -C api db-reset-seed` unless you
explicitly intend to delete local persisted data. The API reset targets affect more than PostgreSQL:
read [`api/Makefile`](../api/Makefile) before using them.

## Configuration

Use the maintained examples rather than copying a second environment-variable catalog:

| File | Scope |
| --- | --- |
| [Root `.env.example`](../.env.example) | Local Compose names, credentials, ports, cache flags, and container endpoints |
| [`api/.env.example`](../api/.env.example) | Host Spring database, queue, mail, auth, and gRPC settings |
| [`rag-chatbot-fastapi/.env.example`](../rag-chatbot-fastapi/.env.example) | Host model, index, storage, and worker settings |
| [`frontend/.env.example`](../frontend/.env.example) | Browser-visible API URLs and client flags |
| [Production example](../.env.production.example) | Self-hosted template; not a substitute for generated secrets and verified provider configuration |

Python settings read root `.env`, then `rag-chatbot-fastapi/.env`; process environment overrides
file values, and service-level values win over the root file. Spring's Make targets load
`api/.env`. The web client exposes its `NEXT_PUBLIC_*` values to browsers: never place secrets
there.

Optional cache flags are covered in [architecture · caches](ARCHITECTURE.md#caches); they are
off on a fresh install.

### Model alternatives

The delivered default path is native Ollama: `LLM_PROVIDER=ollama`,
`LLM_BASE_URL=http://localhost:11434/v1`, `LLM_MODEL_ID=vylinh`, and BGE-M3 embeddings through
`TEXT_EMBEDDING_BASE_URL=http://localhost:11434`. Never put a provider key in a
`NEXT_PUBLIC_*` variable.

An external OpenAI-compatible generation endpoint can be selected with `LLM_PROVIDER=qwen`
(the adapter's OpenAI-compatible path) plus `LLM_BASE_URL` and `LLM_MODEL_ID`. For Apple Silicon,
a local MLX example is:

```bash
# Run in an environment where mlx-lm is installed; it is not a repository dependency.
python -m mlx_lm.server \
  --model mlx-community/Qwen2.5-14B-Instruct-4bit \
  --host 127.0.0.1 --port 8081
```

Point `LLM_BASE_URL` to `http://127.0.0.1:8081/v1`, set the matching `LLM_MODEL_ID`, and keep
`TEXT_EMBEDDING_BASE_URL=http://localhost:11434` with `TEXT_EMBEDDING_MODEL_ID=bge-m3`.
Port 8081 avoids the Spring API's default port 8080. Size the model to the machine; this example
is not a claim of measured throughput or a requirement to use MLX. Tenant content sent to an
external endpoint crosses that provider's boundary.

### Optional local reranking

Reranking is disabled by default. The recommended portable model is
[`Alibaba-NLP/gte-multilingual-reranker-base`](https://huggingface.co/Alibaba-NLP/gte-multilingual-reranker-base):
an Apache-2.0 encoder-only cross-encoder with explicit Vietnamese support. OpenVie sends the 30
fused dense, sparse, and graph candidates to a TEI `/rerank` endpoint before selecting primary
evidence. If TEI is unavailable, retrieval records a fallback metric and preserves the fused order.

On Linux x86-64 or Windows using Docker Desktop's WSL2 backend, start the CPU profile:

```bash
make -C rag-chatbot-fastapi dev-reranker
make -C rag-chatbot-fastapi dev PYTHON=python3.11 DEV_RERANKER_ENABLED=true
```

Linux ARM64 must select TEI's native ARM image rather than emulating x86-64:

```bash
make -C rag-chatbot-fastapi dev-reranker \
  DEV_RERANKER_IMAGE=ghcr.io/huggingface/text-embeddings-inference:cpu-arm64-1.9
make -C rag-chatbot-fastapi dev PYTHON=python3.11 DEV_RERANKER_ENABLED=true
```

On Apple Silicon, use TEI's native Metal build instead of Docker:

```bash
brew install text-embeddings-inference
make -C rag-chatbot-fastapi dev-reranker-native
```

`make dev` sets `DEV_RERANKER_ENABLED=true`, so the native reranker must be running before the
Python application starts; when it is unavailable, retrieval records a fallback metric and keeps
the fused order, but the extra round trip is wasted. Set `DEV_RERANKER_ENABLED=false` when working
without TEI.

Keep that foreground process open, then start the Python application in another terminal with
`DEV_RERANKER_ENABLED=true` as above. The native target binds only to `127.0.0.1:8082`.
Stop it with `Ctrl-C`; stop the Compose variant with
`make -C rag-chatbot-fastapi dev-reranker-down`.

The application and model server must use the same `RERANKER_MODEL_ID`; the Compose service
derives its command from that setting rather than maintaining a second model identifier.
Operators may select `BAAI/bge-reranker-v2-m3` as a larger multilingual alternative, but should
compare retrieval quality and p95 latency on representative data before changing the default.

## Verification

Run checks for the area changed; inspect each command's output rather than treating startup
or a successful build as an end-to-end test.

| Area | Commands from repository root |
| --- | --- |
| Java | `make -C api test` or `make -C api verify` |
| Python | `make -C rag-chatbot-fastapi check PYTHON=python3.11` |
| Web types | `npm --prefix frontend run typecheck` |
| Web lint / translations | `npm --prefix frontend run lint`; `npm --prefix frontend run i18n:check` |
| Web unit tests | `npm --prefix frontend test` |
| Web production build | `npm --prefix frontend run build` |
| Web browser suite | `npm --prefix frontend run test:e2e` (requires Playwright browser installation) |

Python `check` verifies generated protobuf outputs, Ruff, mypy, and pytest. When changing
[`proto/`](../proto/), regenerate Python outputs with
`make -C rag-chatbot-fastapi generate-proto PYTHON=python3.11`; Java generation is part of Maven.
The shared [`contracts/`](../contracts/) schemas and fixtures cover JSON event compatibility
and must remain aligned with both language implementations.

For command discovery without starting services:

```bash
make -C api help
make -C rag-chatbot-fastapi help
npm --prefix frontend run
```

### Retrieval evaluation

The evaluator scores **previously recorded rankings**; it does not run model inference or
create the results file for you. Its inputs are two files you supply: a labeled dataset
(`EVALUATION_DATASET`, Makefile default `tests/data/retrieval_vi_v1.json`, not shipped in this
repository) and a recorded results file (`EVALUATION_RESULTS`, default
`artifacts/full-pipeline.json`). Each dataset example needs an `id` and `relevant_unit_ids`;
results map that id to the ranked `unit_ids` (optionally `channels` and `latency_ms`).

```bash
make -C rag-chatbot-fastapi evaluate-retrieval PYTHON=python3.11 \
  EVALUATION_DATASET=/path/to/your-dataset.json \
  EVALUATION_RESULTS=/path/to/your-recorded-results.json \
  EVALUATION_LABEL=full-pipeline
```

Whatever dataset you use is a correctness fixture until its construction, labeling, and
limitations are documented; do not present its scores as publication-grade evidence.

Focused correctness checks from the repository root:

```bash
make -C rag-chatbot-fastapi test PYTHON=python3.11 \
  PYTEST_ARGS="tests/test_graph_search.py tests/test_hybrid_retrieval.py tests/test_semantic_answer_cache.py tests/test_semantic_answer_cache_integration.py tests/test_digital_formats.py tests/test_compare_retrieval_results.py"
```

The real semantic-cache integration test is skipped without both `REDIS_TEST_URL` and
`QDRANT_TEST_URL`. Use disposable local services: the test uses Redis database
15 and creates/deletes its own Qdrant collection.

### Release evidence

At minimum, exercise the affected authentication/authorization, tenant isolation, ingestion,
citation, idempotency, and failure/recovery paths. Record configuration and model/index
versions when comparing behavior. Model, embedding, parser, and public-contract changes need
applicable regression and workflow evidence, not only a compilation check.

Public chat currently completes as JSON, so measure completed-request latency. First-token and
stream-duration targets are future criteria for a streaming feature rather than claims that it
exists today. Use the [architecture reference](ARCHITECTURE.md) for operational boundaries and
the [deployment guide](DEPLOYMENT.md) for install verification and rollback considerations.

## Troubleshooting

| Symptom | First check |
| --- | --- |
| Containers never started | `make dev` only runs Python; run `dev-infra` separately |
| Host cannot resolve `postgres` / `redis` | Use service-specific host `.env` examples, not container URLs from root `.env` |
| AI ready but answers fail | Check model availability, credentials, embeddings, index/graph access, worker diagnostics, and request logs |
| Document remains pending/failed | Check RabbitMQ, embedded worker mode, object storage, and model/index dependencies; the upload request only accepts the job |
| Python HTTP port confusion | Host Make target uses 18000; container app settings use 8000; internal gRPC uses 50051 |
| Invitations cannot be sent | Verify an email channel is configured in Settings or set `MAIL_PROVIDER`; invitations are refused without an enabled channel |
| A cache behaves inconsistently | Check the flag is on in the owning service's env file; see [caches](ARCHITECTURE.md#caches) before flushing Redis |
| Setup fails after moving the checkout | Check virtual-environment entry points and native build caches for old absolute paths |
| Make or `sed` fails only on Windows | Re-run the command from WSL2 or Git Bash; PowerShell/cmd cannot expand `VAR=value` recipe prefixes or POSIX utilities — see [platform notes](#platform-notes) |
| Python ready but cannot reach Ollama on Windows | `localhost` is not shared between Windows and WSL2 in every networking mode; verify with the reachability commands in [prepare models](#prepare-models) and align `LLM_BASE_URL` / `TEXT_EMBEDDING_BASE_URL` |
| `docker compose` is missing or refuses connections on Linux | Install the Compose plugin, add your user to the `docker` group, and start a new login session so the group membership applies |

Inspect local logs without exposing tokens, credentials, or document text.
Never use destructive database resets as a generic troubleshooting step.
