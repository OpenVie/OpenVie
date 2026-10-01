# Python AI modular-monolith guide

This guide explains how the Python AI service under `app` is organized and how to add features
without breaking its module boundaries.

The service is a modular monolith: one source tree containing six capability modules, composed into
three runtime roles. Module boundaries are the current architecture, not a future migration target.
Architecture tests enforce them on every test run.

The Java API remains the business control plane and owns tenants, documents, conversations, and
PostgreSQL data. Python consumes the context supplied by Java, reads Java-owned document objects,
and owns only its AI indexes, caches, graph projection, and ingestion checkpoints. Do not add a
PostgreSQL driver, business SQL, or direct access to Java-owned tables.

External compatibility is a first-class constraint. An internal refactor must not silently change
protobuf fields or statuses, RabbitMQ payloads, graph HTTP routes, authentication, health paths,
Qdrant or Kuzu identities, Redis keys/codecs, citations, token usage, visibility, or cache-tier
behavior.

## The four non-negotiable rules

### Rule 1: cross modules only through `api` or `api.event`

A capability module may not import another module's `internal` or `transport` package, concrete
service, repository, configuration, cache implementation, or provider adapter.

Allowed cross-module imports are:

- synchronous contracts and their API-owned types from `app.modules.<module>.api`;
- producer-owned durable event types from `app.modules.<module>.api.event`;
- business-neutral technical infrastructure from `app.common`.

```python
# Wrong: retrieval reaches into the index implementation.
from app.modules.index.internal.qdrant_queries import QdrantKnowledgeIndexQuery

# Correct: retrieval depends on the index capability.
from app.modules.index.api import KnowledgeIndexQueryApi
```

Everything outside a module's `api` and `api.event` packages is private to that module, even if a
Python name has no leading underscore. A public importable name is not permission to cross the
module boundary.

Transport code belongs to the module whose external boundary it adapts. It converts protobuf,
RabbitMQ, HTTP, Redis, or SDK values into API-owned types before calling capability code. Generated
protobuf code may be imported only by transport and bootstrap code.

### Rule 2: synchronous boundaries use capability-focused interfaces

When a caller needs an immediate answer or must know whether an operation succeeded, the owning
module exposes a small `Protocol` under its `api` package.

The API package owns every command, result, frozen dataclass, `StrEnum`, and exception that crosses
the boundary. API types must not expose implementation dataclasses, provider objects, Qdrant/Kuzu
models, Redis clients, FastAPI requests, gRPC messages, generated protobuf classes, or other SDK
types.

```python
# app/modules/ingestion/api/__init__.py
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol


class IngestionOutcomeStatus(StrEnum):
    COMPLETED = "COMPLETED"
    DUPLICATE = "DUPLICATE"


@dataclass(frozen=True, slots=True)
class IngestionOutcome:
    status: IngestionOutcomeStatus
    chunk_count: int


class IngestionError(Exception):
    pass


class IngestionApi(Protocol):
    async def process(self, command: IngestDocumentCommand) -> IngestionOutcome: ...
```

Callers depend on the protocol and its API-owned values. Bootstrap selects and injects the concrete
implementation.

Prefer interfaces named after a capability, such as `KnowledgeIndexQueryApi`, `GraphProjectionApi`,
or `TextEmbeddingApi`. Do not expose a concrete service merely because another module needs one
method, and do not create a broad `*ModuleApi` that mirrors every internal operation.

API-owned exceptions are part of the boundary. The implementation translates provider and SDK
failures into errors such as `IndexUnavailableError`, `GraphRejectedError`, or
`ModelTimeoutError`; the caller must not need to import an SDK exception to handle a capability
failure.

API packages are intentionally pure. They may use the Python standard library and other explicitly
allowed API types, but cannot import module internals, transports, frameworks, generated code,
Redis, Qdrant, Kuzu, boto3, aio-pika, or provider SDKs.

### Rule 3: one module exclusively owns each persistent resource or namespace

Only the owning module may read, write, configure, serialize, or delete its persistent resource.
Ownership includes collection names, point identities, payload layouts, Redis key families and
codecs, Kuzu identities, queue behavior, and cleanup logic.

| Persistent resource or namespace | Owner |
| --- | --- |
| Qdrant `knowledge_units_v2` | `index` |
| Qdrant `semantic_answer_cache_v1` | `generation` |
| Kuzu database and graph identities | `graph` |
| Generation-result and semantic-answer Redis keys/codecs | `generation` |
| Embedding Redis keys/codecs | `model` |
| Retrieval Redis keys/codecs | `retrieval` |
| Ingestion event/job/lease Redis keys | `ingestion` |
| RabbitMQ ingestion queue, retries, publications, and DLQ behavior | `ingestion` |
| SeaweedFS document objects | Java `document`; Python has read-only access through `common.storage` |
| PostgreSQL business data | Java modules; Python has no access |

If another module needs indexed knowledge, it calls `index.api`; it does not use `qdrant_client`.
If another module needs graph data, it calls `graph.api`; it does not import Kuzu or the graph HTTP
client. If another module needs a cached result, the owner exposes an API where one is appropriate;
it does not share the owner's Redis key or codec.

Resource ownership applies to maintenance and deletion too. For example,
`DocumentIndexLifecycleApi.delete_document()` coordinates index and graph deletion through their
APIs. It does not let gRPC transport delete Qdrant points or Kuzu nodes directly.

### Rule 4: independent reactions use producer-owned durable events

Use an event when the producer is announcing a completed fact and does not need an immediate return
value. The event contract belongs to the producer under `api.event`, is immutable, and carries only
API-owned or standard-library values.

Do not use an event to disguise a request/response call. If the caller needs a result before it can
continue, use a synchronous API. Do not call another module's internal service for an independent
side effect.

The current cross-process durable event flow is document ingestion. Its canonical request and
status contracts live under `contracts/document-ingestion/v1`; ingestion owns the RabbitMQ
transport and its durable Redis checkpoints. This is a specific integration workflow, not a general
in-process event bus. Do not invent an in-memory event framework or publish untracked background
tasks to satisfy this rule.

When adding a new durable event flow:

1. identify the module that owns the fact;
2. put the immutable event type in that producer's `api.event` package;
3. define and version the serialized contract when it crosses a process boundary;
4. persist enough state to retry safely;
5. use stable event identities and idempotent consumers;
6. confirm publication before advancing durable state;
7. test duplicate delivery, retry, crash recovery, and permanent failure.

## Package model

```text
app/
  modules/
    generation/{api,internal,transport}
    retrieval/{api,internal}
    ingestion/{api,api/event,internal,transport}
    index/{api,internal,transport}
    graph/{api,internal,transport}
    model/{api,internal}
  common/
  bootstrap/
  contracts/
  generated/
  alignment/
  maintenance/
  workers/
```

A capability module normally contains:

```text
<module>/
  api/        supported protocols, boundary values, enums, and exceptions
    event/    immutable durable facts owned by this producer, when needed
  internal/   application logic, configuration values, repositories, and adapters private to owner
  transport/  protobuf, HTTP, RabbitMQ, or other external transport mapping, when needed
```

The non-capability packages have narrow roles:

- `common` contains business-neutral technical helpers such as storage reading, cache primitives,
  metrics, middleware, tracing, concurrency tracking, and generic diagnostics. It must not import a
  capability module.
- `bootstrap` is the composition root. It loads environment settings, constructs clients and
  module implementations, injects APIs, registers transports, composes health, and shuts resources
  down. It may see all modules but must not contain business decisions.
- `contracts` contains canonical external schemas and fixtures.
- `generated` contains generated transport code and is not a business API.
- `alignment` contains the offline alignment evaluator: it replays labeled, anonymized Q/A traces
  against the routing policy without making model calls, training, or promoting anything.
- `workers` is the thin `python -m app.workers.runner` compatibility entrypoint for the dedicated
  document worker.
- `maintenance` calls module APIs or common diagnostics. It cannot import module internals.

The only compatibility entrypoints outside this package model are:

| Runtime role | Entrypoint | Composition root |
| --- | --- | --- |
| AI API and gRPC inference | `app.main:app` | `app.bootstrap.ai_app` |
| Graph HTTP service | `app.graph_main:app` | `app.bootstrap.graph_app` |
| Dedicated document worker | `python -m app.workers.runner document` | `app.bootstrap.worker_runner` |

Entrypoints stay thin. Embedded and dedicated workers must use the same ingestion API and checkpoint
implementation.

## Allowed dependency graph

The capability graph is intentionally acyclic:

```text
generation -> retrieval.api, model.api, common
retrieval  -> index.api, graph.api, model.api, common
ingestion  -> index.api, graph.api, model.api, common.storage
index      -> common
graph      -> common
model      -> common
maintenance-> module APIs and common diagnostics
bootstrap  -> all modules, wiring only
```

Before adding an import, check this graph. If the new edge reverses an existing direction or creates
a cycle, redesign the interaction. Typical solutions are a capability-focused owner API, a
producer-owned durable event, or moving genuinely business-neutral infrastructure into `common`.
Do not move business orchestration into `common` or `bootstrap` merely to hide a cycle.

## Module catalog

| Module | Responsibility | Supported boundaries | Exclusive state/resources |
| --- | --- | --- | --- |
| `generation` | Answer orchestration, prompts, citations, session context, token reporting, conversational shortcuts, spreadsheet calculation, and answer caching | `GenerationApi`, `GenerationCacheMaintenanceApi`; generation-owned requests/results/errors | Semantic-answer Qdrant collection and generation Redis namespaces |
| `retrieval` | Query planning, fingerprints, weighted fusion, diversity, reranking, neighbor expansion, and retrieval caching | `RetrievalApi.plan()`, `RetrievalApi.retrieve()`; retrieval-owned profiles, scopes, units, and errors | Retrieval Redis namespace |
| `ingestion` | Extraction, chunking, entity extraction, index/graph replacement coordination, RabbitMQ processing, checkpoints, cleanup, and recovery | `IngestionApi`, `DocumentIndexLifecycleApi`, `IngestionCheckpointMaintenanceApi`; ingestion commands/outcomes/errors/events | RabbitMQ ingestion behavior and checkpoint Redis namespaces |
| `index` | Knowledge-index replacement, deletion, dense/sparse search, neighbors, and document-unit listing | `KnowledgeIndexCommandApi`, `KnowledgeIndexQueryApi`; index commands/queries/results/errors | Qdrant `knowledge_units_v2` |
| `graph` | Graph projection replacement/deletion and graph search | `GraphProjectionApi`, `GraphQueryApi`; graph batches/queries/results/errors | Kuzu, graph identities, and graph transport implementation |
| `model` | Chat providers, dense embeddings, sparse embeddings, normalization, and embedding caching | `ChatModelApi`, `TextEmbeddingApi`, `SparseEmbeddingApi`; model messages/results/vectors/errors | Embedding Redis namespace and provider adaptation |

The gRPC service delegates through these boundaries:

```text
GenerateAnswer      -> GenerationApi
ListDocumentUnits   -> KnowledgeIndexQueryApi
DeleteDocumentIndex -> DocumentIndexLifecycleApi
```

Transport maps protobuf requests and API results. It does not implement generation, querying, or
deletion policy.

## Choosing an API or an event

Use a synchronous API when the caller cannot continue without the result:

- generation needs a retrieval plan before checking semantic cache;
- retrieval needs embeddings or index/graph search results;
- ingestion must know that index or graph replacement succeeded;
- gRPC must return document units or deletion status;
- maintenance needs an owner to perform bounded cleanup.

Use a durable event when the producer records a fact and the receiver can react independently:

- Java announces that a document should be ingested;
- ingestion reports `PROCESSING`, `COMPLETED`, or `FAILED` to Java;
- a future producer announces a completed fact to independently retryable consumers.

Ask: "Does the producer need the consumer's return value to finish this operation?" If yes, use an
API. If no, and the reaction must survive crashes, use a producer-owned durable event.

## Durable document ingestion

Ingestion checkpoints are independent of `CACHE_ENABLED`. Redis must use AOF with
`appendfsync everysec` and `noeviction`. The owned keys are:

```text
ccn:v1:ingestion:event:{event_id}
ccn:v1:ingestion:job:{job_id}
ccn:v1:ingestion:lease:{job_id}
```

Atomic Lua scripts implement claim, lease renewal, phase transitions, and terminal completion.
Defaults are 30-day retention, a 300-second lease, and a 30-second heartbeat.

```text
CLAIMED -> PROCESSING_PUBLISHED -> INDEX_REPLACED -> GRAPH_REPLACED
        -> COMPLETED_PUBLISHED -> COMPLETE

INDEX_REPLACED -> CLEANUP_PENDING -> FAILED_PUBLISHED -> FAILED
```

Status event IDs are stable UUIDv5 values derived from schema version, job ID, and status. A retry
therefore republishes the same event ID, allowing Java's inbox to deduplicate it.

The ordering rules are part of correctness:

- confirm a RabbitMQ publication before advancing its checkpoint;
- advance the checkpoint before acknowledging the request;
- safely repeat index and graph replacement using deterministic identities;
- retain the index after a transient graph failure and retry graph work;
- after a permanent post-index failure, finish idempotent graph/index cleanup before publishing
  `FAILED`;
- send malformed requests without usable IDs directly to the DLQ;
- publish a valid failed status only when the required IDs can be recovered.

The bounded recovery command republishes the canonical request stored by incomplete checkpoints:

```sh
make recover-ingestion-checkpoints
```

Use Java's existing reindex command for full disaster recovery. When changing this workflow, test
duplicate and concurrent deliveries, payload-hash mismatch, lease expiry, retry exhaustion, cleanup
retry, DLQ handling, and crashes around every publication, checkpoint, replacement, and
acknowledgement boundary.

## Where new code belongs

Use these placement rules before creating a file or type:

| New code | Location |
| --- | --- |
| Cross-module callable protocol | Owning module's `api` package |
| Boundary command, result, enum, or exception | Same owning `api` package |
| Durable fact | Producer's `api.event` package |
| Capability application logic | Owning module's `internal` package |
| Provider or persistence implementation | Resource owner's `internal` package |
| External protobuf/HTTP/RabbitMQ mapping | Owning module's `transport` package |
| Environment loading and object construction | `bootstrap` |
| Business-neutral reusable infrastructure | `common` |
| Canonical cross-process schema or fixture | `contracts` |
| Operational command | `maintenance`, calling module APIs only |

Examples:

- A new Qdrant search mode belongs in `index`. Retrieval supplies an API-owned query and consumes an
  API-owned result; it never imports Qdrant models.
- A new graph search option belongs in `graph.api` and its graph implementation. Retrieval does not
  call the graph HTTP client directly.
- A new model provider belongs in `model.internal`. Generation and ingestion continue depending on
  `model.api` and must not handle provider-specific response or exception types.
- A new citation filter belongs in `generation`, because generation owns answer assembly and its
  external result. It does not belong in `common` merely because several functions use it.
- A generic S3-compatible object reader may live in `common.storage`; document extraction policy
  stays in ingestion.
- A new gRPC field is first an external contract change. Update and verify the protobuf boundary;
  do not pass the generated message into module internals.

## Adding or changing a module boundary

When one module needs a new capability from another:

1. Identify the owner of the behavior and any persistent state involved.
2. Decide whether the interaction needs an immediate result or is an independent reaction.
3. For a synchronous call, add the smallest useful `Protocol` method and API-owned immutable types
   to the owner `api` package.
4. For a durable reaction, define the immutable fact in the producer `api.event` package and design
   persistence, retry, identity, and deduplication before implementing the consumer.
5. Implement the behavior inside the owner module.
6. Wire the implementation to callers in the appropriate bootstrap composition root.
7. Map external transport values at the transport edge.
8. Add owner-boundary tests, caller behavior tests, architecture coverage, and compatibility tests.

If this introduces an edge that is absent from the allowed graph, treat that as a design problem.
Do not add an architecture allowlist, a compatibility re-export, or a late import to bypass the
check.

## External contract safety

Protect these contracts whenever related code changes:

- `proto/cacanode_ai_v1.proto`: RPC names, field numbers, optional presence, enums, and gRPC status
  mappings;
- `contracts/document-ingestion/v1`: RabbitMQ request/status schemas, fixtures, routing behavior,
  retries, DLX/DLQ, and status identities;
- graph HTTP: `/health/live`, `/health/ready`, `/internal/v1/sources/{source_id}`,
  `/internal/v1/search`, `X-Graph-Token`, and JSON shapes;
- AI HTTP: `/health`, `/health/live`, `/health/ready`, and `/metrics`;
- Qdrant: collection/vector names, point IDs, payload fields, filters, and query behavior;
- Kuzu: node and relation identities, replace/delete/search behavior, and transactions;
- Redis: key prefixes, expiry behavior, and serialized codecs;
- generation behavior: answer text, citations, token usage, visibility/privacy, and cache-tier
  semantics.

Prefer additive compatible changes. If an incompatible external change is intentional, version and
coordinate it with Java and deployment consumers rather than hiding it inside a module refactor.

## Enforcement

The architecture suite rejects:

- cross-module imports outside `api` or `api.event`;
- API packages that import internals, transports, frameworks, generated code, or SDK types;
- capability dependencies from `common`;
- cycles in the capability graph;
- generated protobuf imports outside transport/bootstrap;
- Qdrant knowledge-index access outside `index`;
- semantic-answer collection access outside `generation`;
- Kuzu imports outside `graph`;
- aio-pika imports outside ingestion transports;
- boto3 imports outside common storage;
- Redis key strings or codecs outside their owning modules;
- maintenance imports of module internals;
- PostgreSQL drivers, business SQL, and unexpected HTTP routes;
- legacy packages or entrypoint implementations outside the approved package model.

These checks have no final violation allowlist. Fix the dependency or ownership boundary instead of
weakening a test.

Run the full Python verification before opening a PR:

```sh
cd rag-chatbot-fastapi
make check
git diff --check
```

When a change touches a shared contract or deployment composition, also run the relevant Java and
Compose checks from the repository root:

```sh
sh api/mvnw test
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.production.example config --quiet
```

Add focused tests at the owner API/event boundary and behavior tests at the caller or transport.
Durable flows need failure-injection and idempotency coverage, not only a happy-path unit test.

## New-developer checklist

Before implementing a change, answer these questions:

1. Which capability module owns the behavior?
2. Which module owns every persistent resource, key family, codec, queue, or identity involved?
3. Does the caller need an immediate result, or is this an independent durable reaction?
4. If synchronous, is there a small owner `Protocol` with only API-owned types and exceptions?
5. If event-driven, is the event owned by the producer, durable, stable, retryable, and
   deduplicated?
6. Are all cross-module imports from another module's `api` or `api.event` package?
7. Does any API type leak an internal, transport, framework, generated, or SDK type?
8. Does the new dependency preserve the allowed acyclic graph?
9. Are environment loading and cross-module wiring confined to bootstrap?
10. Are protobuf, RabbitMQ, graph HTTP, health, Qdrant, Kuzu, Redis, privacy, and answer contracts
    still compatible?

Common mistakes to avoid:

- importing a concrete implementation because it already has the needed method;
- passing protobuf, Qdrant, Kuzu, Redis, aio-pika, boto3, or provider objects across a module API;
- duplicating another module's Redis key, Qdrant filter, graph identity, or serializer;
- placing capability-specific helpers or orchestration in `common`;
- putting business behavior in `bootstrap` because bootstrap can import every module;
- using an in-memory task or event for work that must survive process failure;
- acknowledging RabbitMQ before confirmed publication and durable checkpoint advancement;
- changing a stable identity, payload, codec, route, or status mapping during an internal refactor;
- hiding a cycle with a late import, type-checking-only import, or compatibility re-export;
- fixing an architecture-test failure by weakening the rule.

If a design cannot satisfy these four rules, stop and redesign the boundary before adding code.
