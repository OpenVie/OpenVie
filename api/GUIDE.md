# Java API modular-monolith guide

This guide explains how the Spring Boot API under `com.cacanode.api` is organized and how to add
features without breaking its module boundaries.

The API is a modular monolith: one repository, one JVM, one deployable application, and one
database, split into business modules with explicit contracts and exclusive data ownership. The
boundaries described here are the current architecture, not a future migration target. ArchUnit
and table-ownership tests enforce them on every test run.

External compatibility remains a first-class constraint. Refactoring an internal module must not
silently change REST paths, JSON contracts, protobuf contracts, authentication behavior, database
table names, or customer-visible behavior.

## The four non-negotiable rules

### Rule 1: cross modules only through `api` or `api.event`

A business module may not import another module's controller, service, query, repository, entity,
internal DTO, configuration, or infrastructure implementation.

Allowed cross-module imports are:

- synchronous contracts and their API-owned value types under `<module>.api`;
- producer-owned event contracts under `<module>.api.event`;
- business-neutral technical infrastructure from `common`;
- generated external transport contracts, such as protobuf classes.

```java
// Wrong: auth reaches into tenant persistence.
import com.cacanode.api.tenant.repository.UserRepository;

// Wrong: chat calls another module's application service.
import com.cacanode.api.tenant.service.TenantWorkspaceService;

// Correct: auth uses a tenant capability.
import com.cacanode.api.tenant.api.TenantIdentityApi;

// Correct: chat validates its workspace through the tenant boundary.
import com.cacanode.api.tenant.api.TenantWorkspaceApi;
```

Controllers belong to one module and call that module's application layer. A controller must not
become a composition layer for another module's implementation.

### Rule 2: synchronous boundaries are capability-focused interfaces

When a caller needs an immediate answer or an atomic decision, the owning module exposes an
interface under `<module>.api`.

The API package owns every command, result, enum, and exception that crosses the boundary.
An API contract must not expose JPA entities, repositories, servlet types, internal DTOs, query
objects, or implementation services.

```java
// tenant.api
public interface TenantIdentityApi {
    UserSnapshot requireUser(UUID tenantId, UUID userId);
}

// auth depends on the interface and its API-owned snapshot.
UserSnapshot user = tenantIdentityApi.requireUser(tenantId, userId);
```

Prefer small interfaces named after a capability. Do not create an umbrella interface merely to
expose an internal service, an empty marker API, or a concrete class named `*ModuleApi`.

Not every module needs a synchronous API. Terminal modules such as `notification`, and modules
that react only to published facts, may expose no callable business boundary. `chat` currently
exports none: its `ChatControlPlaneService` is an internal query/orchestration type.

### Rule 3: one module owns each database table

Only the owning module may access a table through JPA, JDBC, native SQL, `EntityManager`, or any
other runtime persistence mechanism.

A foreign key may point to another module's table, but it does not transfer ownership. The Java
side represents cross-module references as scalar IDs when the referenced entity is owned
elsewhere. For example, `notification` stores tenant, user, and chatbot UUIDs on its own
`notifications` rows; it does not map `tenant`'s entities.

If another module needs data, it must:

1. call an owner API for a synchronous use case;
2. consume an owner event for an independent reaction; or
3. read its own projection, populated from owner events or owner export APIs.

Cross-module reporting joins are not allowed in runtime application code. There is no reporting
module in this release: a reader either owns its projection tables or asks the owner for an export.

### Rule 4: independent reactions use producer-owned durable events

If a producer does not need an immediate return value, it publishes a fact instead of calling the
consumer's service.

Business event contracts live under the producer's `<module>.api.event` package. They are
immutable and carry enough data for consumers without exposing the producer's entities.

Business events that drive notification emails, invitation and verification emails, login 2FA
emails, or refresh-token revocation must use the durable module-event outbox. Do not implement a
new durable consumer with `@TransactionalEventListener(AFTER_COMMIT)` or `@Async`; the relay must
invoke consumers synchronously so it can detect failure and retry safely.

Direct Spring application events remain appropriate for technical, in-process concerns such as
audit recording and cache invalidation.

## Package model

Every first-level business package is a module:

```text
com.cacanode.api.<module>
```

A typical module looks like this:

```text
<module>/
  api/                 supported synchronous contracts and boundary value types
    event/             immutable facts owned by this producer
  controller/          REST endpoints owned by the module
  service/ or query/   application logic and owner-specific queries
  model/               persistence/domain types private to the module
  repository/          persistence access private to the module
  ...                  other module-private implementation packages
```

Everything outside `api` and `api.event` is private to the module, even when Java visibility is
`public`. Public visibility is sometimes required by Spring; it is not permission for another
module to import the type.

Two first-level packages have special roles:

- `common` is the technical shared kernel. It contains business-neutral infrastructure such as
  durable event storage, storage abstractions, cache infrastructure, filters, audit support,
  and generic errors. It must not depend on any business module.
- `bootstrap` is the composition root. It may wire all modules, register event types, configure
  security, run startup commands (`TenantBootstrapCommand`), and expose operational health
  (`ModularReadinessHealthIndicator`). It must not contain business decisions or become a
  shortcut for cross-module orchestration.

## Allowed dependency graph

The business dependency graph is intentionally acyclic:

```text
tenant       -> ai.api
auth         -> tenant.api, auth.api.event
chat         -> ai.api, document.api, tenant.api
document     -> ai.api, tenant.api
notification -> producer api.event packages (auth, tenant)
common       -> no business module
bootstrap    -> all modules, wiring only (startup commands may read owner repositories directly)
```

Before adding a dependency, check this graph. If the new edge reverses an existing direction or
creates a cycle, redesign the interaction as an owner API, a producer event, or an owned
projection.

## Module catalog

| Module | Responsibility | Supported synchronous boundaries | Owned tables / database objects |
| --- | --- | --- | --- |
| `ai` | Model configuration and the gRPC inference/index boundary | `AiInferenceApi` (`generate`, `listDocumentUnits`, `deleteDocumentIndex`), `ModelConfigurationApi`; API-owned AI requests, results, citations, units, and `AiInferenceException` | `model_config_versions` |
| `auth` | Registration/login flows, JWTs, refresh tokens, verification, login 2FA, and authentication abuse controls | No general business API; REST-facing, calls `tenant.api` for identity changes and publishes `auth.api.event` facts | `refresh_tokens`, `login_2fa_state` |
| `chat` | Employee playground conversations, messages, turns, idempotency, and inference orchestration | No exported business API; `ChatControlPlaneService` is chat-internal and consumes `ai`, `document`, and `tenant` APIs | `chat_sessions`, `chat_messages`, `chat_turns` |
| `document` | Document metadata, object storage, ingestion transport, index cleanup, citations, and the reindex command | `DocumentApi.validateCitations` | `documents`, `internal_event_outbox`, `internal_event_inbox` |
| `notification` | In-app notifications and transactional email reactions | No general synchronous business API; consumes producer events | `notifications` |
| `tenant` | Tenants, users, invitations, knowledge bases, and chatbots | `TenantIdentityApi`, `TenantWorkspaceApi`; API-owned snapshots, `RegisterTenantCommand`, `TenantUserResult` | `tenants`, `users`, `invitations`, `knowledge_bases`, `chatbots` |
| `common` | Shared technical infrastructure only | Not a business API | `audit_logs`, `module_event_outbox`, `module_event_inbox` |
| `bootstrap` | Application composition, security wiring, event registry, startup commands, and readiness | Not a business API | No business tables |

The document ingestion outbox/inbox is owned by `document` and handles its RabbitMQ ingestion
transport. The module event outbox/inbox is shared technical infrastructure owned by `common` and
handles durable reactions inside the modular monolith.

These eighteen tables are the complete persistence surface of the API. `TableOwnershipTest`
encodes exactly this ownership map.

## Choosing an API or an event

Use a synchronous API when the caller needs the answer before it can continue:

- authenticate a user or resolve an identity snapshot;
- validate an active workspace before creating a chat turn or uploading a document;
- validate citations before returning an answer;
- resolve the active model configuration.

Use a durable event when the producer is announcing a completed fact and consumers can react
independently:

- a tenant was created, a user was invited, or a user was deactivated;
- a user registered or a login 2FA challenge was requested;
- a document state changed or a document was deleted;
- a refresh token should be revoked after user deactivation.

A useful test is: "Would the producer transaction need the consumer's return value?" If yes, use
an API. If no, publish a fact. Do not use events to hide a synchronous request/response call, and
do not use a synchronous consumer call for an independent side effect.

## Durable module events

### Producer workflow

A producer publishes inside the same transaction as its business mutation:

```java
@Transactional
public void inviteUser(...) {
    // Mutate the producer-owned aggregate.
    durableEventPublisher.publish(
            "tenant.user.invited.v1",
            1,
            new UserInvitedEvent(...));
}
```

The stable type is an externalized persistence contract. Once deployed, do not rename it or reuse
it for a different payload. Add a new version when the serialized contract changes incompatibly,
and register the type/version in `bootstrap.config.ModuleEventRegistryConfig`. The registered
types today are:

```text
tenant.created.v1
tenant.user.invited.v1
tenant.user.deactivated.v1
auth.user.registered.v1
auth.login-2fa.requested.v1
```

The durable flow is:

```text
producer transaction
  -> insert JSON into module_event_outbox
  -> commit
scheduled relay locks a due batch
  -> registry deserializes stable type + version
  -> Spring publishes the typed event synchronously
  -> each consumer claims its inbox key and commits its own mutation
  -> relay marks the event PUBLISHED after every consumer returns
```

If publication or a consumer fails, the relay records the error and retries with bounded
exponential backoff. After the configured attempt limit, the event is marked `DEAD` for operator
attention.

### Consumer workflow

Every durable consumer needs a stable, unique consumer name and must claim the event in the same
transaction as its mutation:

```java
@EventListener
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void handleUserRegistered(UserRegisteredEvent event) {
    if (!inboxService.claim("notification.welcome-email")) {
        return;
    }

    notificationService.sendAndRecordWelcomeEmail(...);
}
```

This produces at-most-once successful processing per `(consumer_name, event_id)` while allowing a
failed consumer to be retried. On redelivery, consumers that already committed their inbox row are
skipped, while the failed consumer gets another attempt.

Current consumer names are `notification.welcome-email`, `notification.login-2fa-email`,
`notification.invitation-email`, and `auth.refresh-token-revocation`.

Consumer requirements:

- use synchronous `@EventListener`;
- use `REQUIRES_NEW` so each consumer commits independently of the relay and other consumers;
- claim before mutating;
- do not swallow failures that should trigger retry;
- do not add `@Async` to a durable listener;
- keep the consumer name stable across refactors;
- make external effects logically idempotent where the downstream system supports idempotency.

The core implementation lives in:

- `common.event.durable.DurableEventPublisher`;
- `common.event.durable.ModuleEventOutboxRelay`;
- `common.event.durable.ModuleEventInboxService`;
- `bootstrap.config.ModuleEventRegistryConfig`.

### Document ingestion transport

Document ingestion does not use the module-event outbox. `document` owns a separate internal
transport: `DocumentIngestionPublisher` writes `internal_event_outbox`, `InternalEventOutboxRelay`
delivers to RabbitMQ through `RabbitDocumentIngestionPublisher`, and `DocumentStatusEventListener`
consumes status events back into `documents`. Keep that path separate from business module events.

## Where new code belongs

Use these placement rules before creating a class:

| New code | Location |
| --- | --- |
| Cross-module callable interface | Owning module's `api` package |
| Boundary command, result, enum, or exception | Same owning `api` package |
| Published business fact | Producer's `api.event` package |
| REST request/response used only by one module's controllers | That module's internal `dto` package |
| JPA entity or repository | Owning module's `model` or `repository` package |
| Owner-specific JDBC query | Owning module's `query` or `repository` package |
| Cross-module wiring or registry | `bootstrap` |
| Business-neutral reusable infrastructure | `common` |
| Projection or read model | Owner-owned tables populated from events or exports |

Examples:

- A new chat field belongs in `chat`, its chat DTOs/events, and the existing chat-owned table. It
  does not belong in `tenant` merely because the chat session has a tenant ID.
- A new tenant value needed by `document` is returned by `TenantWorkspaceApi` or copied through a
  tenant event. Document must not add a query against `tenants`.
- A new email reaction belongs in `notification`, consuming an event owned by the module where the
  fact occurred. The producer must not call `NotificationService`.
- A generic object-storage implementation can live in `common.storage`; document-specific upload
  policy stays in document.

## Database and migration rules

Flyway remains one ordered migration stream because the application deploys atomically
(`V1__baseline_schema.sql`, `V2__seed_default_model_configuration.sql`). Table ownership still
applies to every statement within a migration.

When changing the schema:

1. identify the owning module;
2. keep the table name and external database contract stable unless a coordinated migration says
   otherwise;
3. prefer additive, backward-compatible changes;
4. preserve existing foreign keys unless the migration explicitly replaces them;
5. update the owning entity/repository and its boundary events or snapshots;
6. add migration coverage with representative data for production-specific SQL.

Migrations are excluded from the runtime SQL ownership scan because a migration may coordinate
several modules in one atomic deployment. Runtime Java code receives no such exemption.

## Enforcement

`ModularMonolithArchitectureTest` enforces that:

- the business modules (`ai`, `auth`, `chat`, `document`, `notification`, `tenant`) import another
  module only through `api` or `api.event`;
- API contracts do not leak internal entities, repositories, services, queries, or DTOs;
- `common` does not depend on a business module;
- the business-module graph is acyclic;
- every type ending in `ModuleApi` is an interface;
- JDBC and `EntityManager` access stays in an owner `repository`/`query` package, `common`, or
  `bootstrap`.

`TableOwnershipTest` scans runtime Java persistence references and rejects access to a known table
from a non-owner module.

These tests have no violation allowlist. If an architecture test fails, fix the boundary rather
than weakening the rule or adding an exception for the new dependency.

Run the complete API verification before opening a PR:

```sh
cd api
sh mvnw test
git diff --check
```

Add focused tests at the owner API or event boundary as well as behavior-level controller/service
tests. Durable flows should cover producer rollback, relay retry/dead-letter behavior, inbox
deduplication, and logical consumer idempotency when relevant.

## Operations and recovery

The `modularReadiness` health contributor reports:

- whether Flyway migration version `24` is recorded as successful in `flyway_schema_history`;
- pending module-event count;
- dead module-event count;
- age of the oldest pending event.

Known issue: this release's migration stream stops at `V2`, so the version-`24` lookup never
matches and `modularReadiness` reports `DOWN`. The check has not been re-pointed at an existing
migration yet; treat the pending/dead event details as the usable signal until it is.

The readiness group includes `modularReadiness`. Its default maximum pending age is 30 seconds,
configurable through `app.module-events.readiness-max-pending-age-seconds`.

Readiness becomes `DOWN` when the migration check fails, the health query fails, or pending event
age exceeds the configured limit. A terminal `DEAD` event is reported as a degraded operational
state through the health details, but it does not make an otherwise serving API reject traffic
indefinitely. Operators must still repair or replay every dead event.

Monitor outbox age, retries/dead letters, consumer failures, and the operational failure read
model. A `DEAD` event or a growing pending age is an operational incident, not a reason to bypass
the event boundary.

## New-developer checklist

Before implementing a change, answer these questions:

1. Which module owns the behavior?
2. Which module owns every table involved?
3. Does the caller need an immediate value, or is this an independent reaction?
4. If synchronous, is there a capability-focused owner API with API-owned value types?
5. If asynchronous, is the event owned by the producer and published durably?
6. Does every durable consumer claim a stable inbox name in the same transaction as its mutation?
7. Are all imported business types from the same module, another module's `api`, or
   `api.event`?
8. Does any API type leak an entity, repository, internal DTO, servlet type, or implementation?
9. Does runtime SQL touch only tables owned by the current module?
10. Are REST, JSON, protobuf, authentication, and database contracts still compatible?

Common mistakes to avoid:

- importing an implementation because it is already a Spring bean;
- sharing a JPA entity across module boundaries instead of using a UUID and owner API;
- placing business DTOs or orchestration in `common`;
- putting business behavior in `bootstrap` because it can see all modules;
- publishing a durable event outside the producer transaction;
- using `@Async` or after-commit listeners for durable consumers;
- swallowing a consumer exception and causing the relay to mark an incomplete event as published;
- reading another module's table for a convenient report;
- routing document ingestion through the module-event outbox (it has its own transport);
- fixing an architecture-test failure by relaxing the rule.

If a design cannot satisfy these four rules, stop and redesign the boundary before adding code.
