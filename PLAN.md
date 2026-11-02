# OpenVie v1.0.0 — Full DX-OS 3-Tier Release & BizApp Platform (Tier 3 / Tầng 3)

> Target version: **v1.0.0** · Direct graduation from `v0.3.2` to **v1.0.0** (skipping any `v0.4.x` / `v0.5.x` interim release). No `v0.4.0` release was ever published (`README.md` roadmap stops at `v0.3.1`, `CHANGELOG.md` stops at `0.3.2`; only `frontend/package.json` carries an unreleased `0.4.0` bump), so everything the BizApp platform has shipped in the tree so far is hardened, completed, and released **together with** this plan as **v1.0.0** — marking completion of all three DX-OS tiers (Tier 1 Core PaaS + Tier 2 H-P-D-I UI + Tier 3 BizApp Platform) under frozen SemVer 2.0 public contracts.
>
> **Principle (unchanged):** reuse Flowise-style *design patterns* (schema-driven nodes, encrypted credential registry, two-layer marketplace) on OpenVie's headless services. **No runtime dependency on Flowise, and no Flowise code, templates or assets are copied** (see §1, L4).
>
> **Hard rule for this release:** every change MUST comply with the Apache License 2.0 in [`LICENSE`](LICENSE) and with [`docs/OPEN_SOURCE.md`](docs/OPEN_SOURCE.md). §1 turns the license clauses into concrete, CI-enforced criteria; no feature workstream may merge without passing them.

---

## 0. Baseline audit (what is really in the tree) and scope

Audit date: 2026-11-02, by reading the files cited. Items marked **[verify]** are claims I could not confirm from the repository and MUST be confirmed at the start of the workstream that depends on them.

### 0.1 Delivered in tree (unreleased BizApp baseline as built)

| Area | As built | Evidence |
| --- | --- | --- |
| Schema `bizapp.v1`, validator, parser, install/uninstall (soft), frozen manifest | Done | `bizapp/service/BizApp{ManifestValidator,ManifestParser,InstallService}.java` |
| Record store (JSONB, workspace + org scoped, RBAC per request, audit) | Done | `bizapp/service/BizAppRecordService.java`, Flyway `V12` |
| Blocks with executors | `approval_workitem`, `notify`, `webhook_out`, `external_call` | `bizapp/service/block/*` |
| Vault AES-256-GCM (`BIZAPP_ENC_KEY`, `BIZAPPS_ENABLED`) | Done | `BizAppCredentialCipher`, `BizAppCredentialService`, `.env.example` |
| Webhooks `bizapp.<kind>.record.*` via AFTER_COMMIT bridge | Done | `integration/webhook/BizAppWebhookBridge.java` |
| Marketplace: bundled `crm.json`, `booking.json` + org templates | Done | `resources/marketplace/bizapps/`, `BizAppMarketplaceService` |
| Frontend: apps home, runtime, admin marketplace + vault; registry/timeline/report views | Done | `frontend/src/app/[locale]/(dashboard)/apps`, `(admin)/admin/apps`, `components/bizapp/*` |
| CI: `marketplace-validate` job; Java bundled-template test | Done | `.github/workflows/ci.yml`, `scripts/marketplace_validate.py`, `BizAppBundledTemplatesTest` |
| Docs: `BIZAPPS.md`, `DX_OS.md` Tier-3 flipped, `ARCHITECTURE.md`, `INTEGRATION.md`, `.env.example` | Done | `docs/*` |

### 0.2 Gaps and defects found by the audit

| # | Finding | Evidence | Severity |
| --- | --- | --- | --- |
| G1 | **Report SQL is not tenant- or table-confined.** `report.views[].sql` is validated only by a token blacklist and `:tenant`/`:app` bind names (`isSelectOnly`); it never *requires* a tenant predicate or restricts tables, and runs through the shared `NamedParameterJdbcTemplate` on the core database. A manifest can read other tenants' `bizapp_records`, `users`, `bizapp_credentials` (ciphertext), or call database functions. Not exploited here — derived by reading the code. | `BizAppManifestValidator.isSelectOnly`, `bizapp/query/BizAppRecordViewQuery.java` | **Critical** (breaks multi-tenant isolation; amplified by AI-generated and imported manifests in this release) |
| G2 | **`external_call` has no SSRF guard.** Org-defined `url`, no scheme restriction, no private/loopback/link-local/metadata-range block, default redirect behavior of `SimpleClientHttpRequestFactory`, response body discarded. | `ExternalCallBlock.java` | **High** |
| G3 | `assistant` and `knowledge_base` are accepted block types but have **no executor** and no frontend view; no bundled template uses them. | `BizAppManifestValidator.BLOCK_TYPES` vs `service/block/*` | Medium (declared-but-undelivered) |
| G4 | Reports run on PostgreSQL, not DuckDB; no `bizapp_*` dataset in the Lakehouse CDC. (The old DoD claimed DuckDB.) | `BizAppRecordViewQuery`; no `bizapp` in `LakehouseCdcPublisher` | Medium |
| G5 | `bizapp-single-approval.bpmn20.xml` has a user task and a decision delegate only — **no SLA timers**, no license header. | the BPMN file | Low |
| G6 | `README.md` roadmap and `CHANGELOG.md` have no BizApp entry. | grep | Low |
| G7 | License hygiene gaps — see §1.2 (no dependency-license gate, inaccurate NOTICE, empty POM license metadata, no LICENSE/NOTICE in images, header script blind to SQL/BPMN/YAML). | §1.2 | **High** for an Apache-2.0 release |

### 0.3 Scope of v1.0.0 (everything below ships; nothing is silently dropped)

| ID | Workstream | Delivers |
| --- | --- | --- |
| **L** | License & supply-chain compliance | §1 — gates, NOTICE, headers, dependency inventory, template provenance |
| **S** | Security baseline (blocks A–F) | Declarative report views replace raw SQL (G1); hardened outbound HTTP (G2); vault rules; manifest limits |
| **K** | Complete declared blocks + Lakehouse | `knowledge_base` + `assistant` blocks (G3); `bizapp_records` CDC + optional lakehouse report engine (G4); approval SLA timers (G5) |
| **T** | Typed storage for platform apps (was "C") | Real SQL tables for flagship templates; org-custom apps stay on JSONB |
| **D** | Knowledge projection (was "D") | Records → app KB documents (and digest) so chat/graph can answer over CRM data, with fail-closed access rules |
| **A** | AI manifest drafting | "Describe the process → draft manifest", draft-only, validator-gated |
| **B** | Visual composer (canvas) | React Flow editor producing the same manifest |
| **F** | Template exchange | Export/import/remix with provenance + license metadata; optional allow-listed remote catalog |
| **E** | e-Signature via `external_call` | Hardened outbound call + response mapping + bundled recipe template; **no in-process signing, no key custody** |
| **R** | v1.0.0 GA Release & SemVer freeze | Unified `1.0.0` version across `api/pom.xml`, `rag-chatbot-fastapi/pyproject.toml`, `frontend/package.json`; frozen public contracts (`bizapp.v1`, `openvie.template.v1`, `/api/v1/**`, `openvie.ai.v1` proto); bilingual docs, DX_OS, README roadmap, CHANGELOG, publication gate |

### 0.4 Explicit non-goals (record in `docs/BIZAPPS.md`)

- In-process digital signing, signature verification libraries, or holding signing keys (legal-risk decision: e-signature is delegated through `external_call` only).
- Inbound provider callback endpoint (status is written back by the org's own integration through an `ovk_…` API key, see E-4).
- Org-defined typed tables / DDL generated from tenant manifests (catalog bloat + DDL privileges; typed storage is **platform-template-only**).
- Text-to-SQL or LLM-written queries of any kind.
- Remote-template cryptographic signature verification (v1.0.0 pins a SHA-256 from the catalog index; detached signature verification is post-1.0 work).
- Multi-step BPMN designer (only the single-level approval process; two-level approval remains the document workflow).

---

## 1. License & supply-chain compliance (workstream L — runs first, gates everything)

### 1.1 Apache-2.0 clause → criterion → enforcement

| Clause in `LICENSE` | Criterion for v1.0.0 | Deliverable / gate |
| --- | --- | --- |
| §4(a) recipients get a copy of the License | Every distributable artifact carries `LICENSE` + `NOTICE`: API jar (`META-INF/`), API / frontend / rag-chatbot Docker images (`/licenses/`), source export | Dockerfiles `COPY LICENSE NOTICE /licenses/`; Maven resource copies both into `META-INF/`; CI job `artifact-license` checks presence |
| §4(b) modified files carry prominent change notices | Any third-party-derived file keeps its original header and adds a `Modified by OpenVie: <what>` line | Register in `docs/OPEN_SOURCE.md` (new "Vendored and derived files" table); initial entry: `frontend/public/pdf.worker.min.mjs` (pdf.js, Apache-2.0, banner verified present) |
| §4(c) retain notices of the Work in derivatives | No copying from Flowise (or any project) without preserving its notices; **default is: do not copy** — templates, schemas, icons and canvas UX are original | Policy in `CONTRIBUTING.md`; reviewer checklist item |
| §4(d) NOTICE propagation | `NOTICE` lists every redistributed third-party component with noteworthy terms and stays **accurate** | Rewrite the "Remaining … dependencies are permissive" sentence (see L-2); `notice-check` step compares the generated inventory against `NOTICE` |
| §5 contributions under the License | Unchanged | `CONTRIBUTING.md` already covers; add the dependency policy (L-3) |
| §6 no trademark grant | Third-party names are used only to describe origin/compatibility; **no third-party logos, product names in template titles, or vendor-branded sample data**; e-sign recipe uses neutral provider placeholders | Template review checklist; `marketplace_validate.py` rejects a denylist of vendor names in `name`/`icon` |
| §7/§8 disclaimer and liability | AI-drafted manifests and the e-sign recipe are *tools*: docs state no warranty, no legal validity statement (see §10) | `docs/BIZAPPS.md` "Legal boundaries" section (vi + en) |
| Header on authored sources (project rule) | Extend header coverage beyond Java/Py/TS/proto/sh | L-4 |

### 1.2 Findings and fixes

**L-1 — Dependency license gate (new).** No job today verifies dependency licenses.

- Java: `org.codehaus.mojo:license-maven-plugin` (`add-third-party`, fail on non-allow-listed license) wired into `make -C api verify`. **[verify]** exact goal/flags at adoption.
- Node: `license-checker-rseidelsohn --production --onlyAllow "<allow-list>"` in the `frontend` job. **[verify]**
- Python: `pip-licenses --allow-only="<allow-list>"` in the `python` job. **[verify]**
- Policy (ASF third-party categories): **allowed** Apache-2.0, MIT, BSD-2/3-Clause, ISC, 0BSD, PostgreSQL, Unlicense/CC0, Python-2.0; **allowed with a NOTICE line, unmodified, not statically combined** MPL-2.0, EPL-2.0, CDDL+Classpath; **forbidden as linked/bundled dependencies** GPL, AGPL, LGPL, SSPL, RSAL, BUSL, "Commons Clause", unknown. Network-separate services (Grafana AGPL) remain allowed as documented in `NOTICE`.
- Output: generated `THIRD_PARTY_LICENSES.md` attached to CI artifacts (not committed).

**L-2 — `NOTICE` accuracy.** `NOTICE` claims remaining Java/Python/JS dependencies are "Apache-2.0, BSD, or MIT". Items to check and record (from memory — **[verify]** each with the L-1 inventory):

- `javax.annotation:javax.annotation-api:1.3.2` (`api/pom.xml`) is, to my knowledge, CDDL-1.1 + GPLv2-with-Classpath-Exception. Replace with an Apache-2.0 alternative for the generated gRPC `@Generated` annotation (e.g. `org.apache.tomcat:annotations-api`) or document it as an allowed CDDL+CPE component.
- `com.h2database:h2` (test scope; MPL-2.0 / EPL-1.0) — test-only, not redistributed; note in `NOTICE`.
- `sharp` (frontend `overrides`; Apache-2.0) ships prebuilt libvips binaries (LGPL-3.0-or-later). Confirm whether they reach the production image (Next image optimization); record in `NOTICE` or disable the optimizer in the image.
- `pdf.worker.min.mjs` (pdf.js, Apache-2.0): add a `NOTICE` line.
- Models (`vylinh`, `bge-m3`) and any model used by AI drafting (A): already "not relicensed"; add the drafting model id to the NOTICE model list.

**L-3 — Dependency policy for this release.** New dependencies allowed, each license-checked at adoption and added to `NOTICE` if it has noteworthy terms:

| Dependency | Scope | License (to confirm) |
| --- | --- | --- |
| `@xyflow/react` | frontend runtime (B) | MIT |
| `@dagrejs/dagre` | frontend runtime (B auto-layout) | MIT |
| `jsonschema` | CI python only (`marketplace-validate`) | MIT |
| `license-maven-plugin`, `license-checker-rseidelsohn`, `pip-licenses` | build/CI only | to confirm |

**Explicitly rejected** (recorded so nobody re-adds them): iText (AGPL), EU DSS (LGPL-2.1) and any signing library — superseded by decision E; `elkjs` (EPL-2.0) for layout — dagre is MIT.
No new Java runtime dependency is planned (report compiler and manifest validation are hand-written).

**L-4 — Header coverage.** `scripts/license_headers.py` globs cover only Java/Python/TS/TSX/proto/sh. Extend to:

- New SQL migrations **V14 and later** only (`--` comments). **Do not touch V1–V13**: adding a header changes the Flyway checksum and fails validation on existing databases. Implement as "SQL files with version ≥ 14".
- BPMN files under `api/src/main/resources/processes/` (XML comment after the XML declaration; confirm Flowable still deploys — G5 file is touched anyway).
- `.github/workflows/*.yml`, `docker-compose*.yml`, `Dockerfile*`, `frontend/**/*.mjs`, `scripts/**/*.py` (`#`).
- JSON cannot carry comments → provenance lives in the template envelope (L-5) and in a `resources/marketplace/README.md` stating the folder is Apache-2.0, copyright LinkedNodeDigital.

**L-5 — Template provenance (marketplace remix/import is a redistribution path).** Bundled and imported templates travel in an envelope (see F):

```jsonc
{ "envelope": "openvie.template.v1",
  "meta": { "author": "…", "license": "Apache-2.0",   // SPDX id
            "source": "bundled|org|import:<host>", "sha256": "…",
            "generator": "human|ai", "model": null,
            "changes": [ { "by": "orgId", "at": "2026-11-02T00:00:00Z", "summary": "…" } ] },
  "manifest": { … } }
```

Rules: remix **retains** upstream `author/license/source` and **appends** a `changes[]` entry (mirrors §4(b)/(c)); import accepts only the SPDX allow-list (Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, CC0-1.0, CC-BY-4.0); anything else is rejected with a clear error. All bundled sample data is invented (no customer data — `CONTRIBUTING.md`, publication gate).

**L-6 — Package metadata & unified `1.0.0` version.** `api/pom.xml`: set `<version>1.0.0</version>` (resolves into OpenAPI info block via `@project.version@`) and populate empty `<licenses>` (`Apache-2.0`), `<developers>`, `<scm>`. `frontend/package.json`: set `"version": "1.0.0"` and add `"license": "Apache-2.0"` (stays `"private": true`). `rag-chatbot-fastapi/pyproject.toml`: set `version = "1.0.0"` and `license = { text = "Apache-2.0" }`.

**L-7 — Docs.** `docs/OPEN_SOURCE.md`: add "Dependency policy" and "Vendored and derived files" sections (vi + en). `CONTRIBUTING.md`: dependency policy + "no copying from Flowise or any project without preserving notices; never copy anything from a project's commercial/enterprise directories (Flowise keeps `packages/server/src/enterprise` under a separate commercial license — **[verify]** before citing)".

**L acceptance:** CI jobs `license-headers` (extended), `dependency-licenses`, `artifact-license` green; `NOTICE` diff reviewed; a deliberately-added GPL dependency in a throwaway branch makes `dependency-licenses` fail.

---

## 2. Data model — Flyway V14–V17

Baseline stays V10–V13 (do not edit). All new files carry the header (L-4). Conventions follow V12 (`org_id`, `tenant_id` = workspace, `uuid` ids, `created_by` FK to `users`).

| Migration | Content |
| --- | --- |
| `V14__bizapp_drafts.sql` | `bizapp_drafts(id, org_id, created_by, source CHECK IN ('ai','canvas','import','remix'), title, manifest jsonb, validation jsonb, meta jsonb, created_at, updated_at)` + index `(org_id, updated_at DESC)`. Drafts are never executable; only `install` reads them after re-validation. |
| `V15__bizapp_template_meta.sql` | `bizapp_marketplace_templates`: add `meta jsonb NOT NULL DEFAULT '{}'`, `source varchar(32)`; backfill `source='org'`. |
| `V16__bizapp_crm_leads.sql` | Typed table for the bundled CRM entity (columns generated from `crm.json` fields: varchar(maxLen), numeric, boolean, date; enum → varchar + `CHECK`), plus `org_id, tenant_id, status, runtime jsonb, created_by, updated_by, timestamps`; indexes `(tenant_id, updated_at DESC)` and per-enum filters; **data move**: copy existing `bizapp_records` rows for `(app_kind='crm', entity=<leads>)` then delete them (clean cutover, no dual store). |
| `V17__bizapp_booking_appointments.sql` | Same pattern for the bundled booking entity. |

`DX_OS.md` table count ("27 business tables") is recomputed from `CREATE TABLE` statements across migrations at release time (expected 30: +drafts, +crm leads, +booking appointments), not typed by hand.

---

## 3. Canonical manifest schema `bizapp.v1` (frozen 1.0 contract)

Because no `0.4.0` release was ever cut, the pre-release draft shape in the tree (which allowed free-form report `sql`) is replaced **in-place** before the `v1.0.0` cut so `v1.0.0` ships a clean, secure **`bizapp.v1`** as its first frozen public schema (under SemVer 2.0, any breaking change after `v1.0.0` requires `bizapp.v2` + migration). The file `contracts/bizapp/bizapp.v1.schema.json` (JSON Schema 2020-12, in the existing shared `contracts/` folder) is the single source of truth for: the Java validator tests, `scripts/marketplace_validate.py`, frontend types, AI structured output, and the canvas inspector forms. The Java `BizAppManifestValidator` remains the **authority** (the schema is necessary, not sufficient).

Hardened `bizapp.v1` changes versus the unreleased draft in the tree:
1. **Reports are declarative, no SQL string** (fixes G1):

   ```jsonc
   { "id": "report", "type": "report", "params": { "views": [{
       "name": "pipeline_by_stage", "entity": "leads",
       "groupBy": ["stage"],
       "metrics": [ { "fn": "count", "as": "leads" }, { "fn": "sum", "field": "dealValue", "as": "value" } ],
       "filters": [ { "field": "stage", "op": "in", "values": ["NEW", "CONTACTED"] } ],
       "orderBy": [ { "by": "leads", "dir": "desc" } ], "limit": 100,
       "engine": "postgres"                      // or "lakehouse" (K-3)
   }] } }
   ```

   Allowed: `fn ∈ {count,sum,avg,min,max}`, `op ∈ {eq,ne,in,gt,gte,lt,lte,contains,isNull}`; every `field`/`groupBy` must be a declared entity field (numeric fields only for sum/avg); filter values are bound parameters (literal or `{ "param": "x" }` typed at run time); `limit ≤ 500`. There is no free-form SQL anywhere in the manifest.
2. **Field type `ref`** (`{ "type": "ref", "entity": "customer" }`): soft reference (uuid), checked on write to exist in the same org + app. Used by projection text (D) and canvas edges (B).
3. **Entity `knowledge`** (opt-in, D): `{ "project": true, "titleField": "name", "fields": ["name","stage"] }` — explicit field allow-list; per-field `pii: true` is never projectable.
4. **`storage`**: `"jsonb"` (default) or `"typed"`; `typed` is honored **only** for platform-bundled templates (validator rejects it for org templates, drafts, and imports).
5. **`ui.layout`** (B): `{ "<id>": { "x": number, "y": number } }`, numeric and bounded; ignored by the runtime.
6. **Limits** (enforced by validator, also on drafts/imports): manifest ≤ 256 KiB, ≤ 20 entities, ≤ 40 fields/entity, ≤ 40 blocks, ≤ 20 views, strings ≤ 200 printable chars as today.

---

## 4. Workstream S — security baseline (blocking; lands before K, T, D, A, B, F, E)

**S-1 Report compiler (replaces G1).** New `bizapp/query/BizAppReportCompiler`: turns a validated view spec into a parameterized statement. Invariants (unit-tested by property tests):

- `FROM` is fixed by the **storage adapter**: `bizapp_records` JSONB (`WHERE org_id = :org AND tenant_id = :tenant AND app_kind = :app AND entity = :entity AND status='ACTIVE'`) or the typed table; the tenant/app/entity predicates are appended by the compiler, never by the manifest.
- Identifiers come only from the validated entity schema; JSONB access uses a bound key (`payload ->> ?`) or typed column names matched against `^[a-z][a-z0-9_]{0,63}$`.
- Executed in a `READ ONLY` transaction with `statement_timeout` and the existing 500-row cap.
- `BizAppManifestValidator.isSelectOnly` and the SQL token blacklist are **deleted**.
- Two dialects (postgres, duckdb) share the same spec (K-3).

**S-2 Safe outbound HTTP.** New `common/net/SafeOutboundHttp` used by `external_call`, remote catalog fetch (F) and any future block:

- `https` only (property `app.bizapps.outbound.allow-http=false`, true allowed only in the dev profile).
- Host must be on the org's allow-list stored in installation `config.allowedHosts` (set by ORG_OWNER via `PATCH /bizapps/{kind}/config`) — empty list ⇒ block disabled.
- Resolve DNS, **pin the resolved address for the connection**, reject loopback, link-local (incl. `169.254.169.254`), private (RFC 1918, `fc00::/7`), multicast, unspecified; **redirects disabled**; connect 10 s / read 15 s; response ≤ 256 KiB; no `Authorization` forwarding across hosts.
- Never log URLs with query strings or any header value.

**S-3 Vault rules.** Credential create rejects values matching PEM private-key blocks (`-----BEGIN … PRIVATE KEY-----`) so signing keys are never custodied (supports E); `credentialRef` resolves by id **or name** within the org only (as the old plan stated; today only UUID works); ciphertext columns are excluded from CDC, exports and audit payloads.

**S-4 Manifest limits and untrusted-input posture.** Drafts (AI/import/canvas) are data until `install` re-runs the full validator; `install` ignores any `validation` stored on a draft.

**S tests (red-team fixtures in `api/src/test/resources/bizapp/redteam/*.json`):** every item below must be rejected at validate time **and**, where reachable, neutralized at run time: free-form `sql` field; report on a non-existent field; `filters.field` containing SQL; cross-org read via crafted view; `external_call` to `http://`, `https://127.0.0.1`, `https://169.254.169.254`, a name resolving to a private IP, a 302 to an internal host; credential with a PEM private key; `storage: typed` on an org template; oversize manifest; `credentialRef` of another org. Plus the isolation gate: install in org A → org B gets 404 for app, records, reports, drafts, templates.

---

## 5. Workstream K — complete declared blocks, Lakehouse, SLA

**K-1 `knowledge_base` block.** Install creates (or reuses) one KB per app per workspace through a new published `TenantKnowledgeApi`/existing tenant boundary **[verify which `*Api` already exposes KB creation; extend it, do not reach into `tenant` internals — ArchUnit forbids]**; `graphExtraction` param maps to the existing graph-extraction setting. Uninstall keeps the KB (records-retained policy).

**K-2 `assistant` block + `AssistantView`.** New published `chat` boundary `ChatRuntimeApi` (none exists today: only `ai`, `tenant`, `document`, `notification`, `workflow` have `*/api/`) wrapping `ChatControlPlaneService` so the block never touches chat internals. The assistant is **scoped to the app's KB** (`GenerationRequest.knowledgeBaseId`, `documentIds`) and keeps all existing guarantees: SSE, `[S1]` citations validated against document visibility, abstention without evidence. **Retrieval code is not modified.** Frontend `AssistantView` reuses the existing chat components with the KB scope; `BizAppRenderer` gains the `assistant` prefix.

**K-3 Lakehouse.** Publish `bizapp_records` rows (payload + metadata; **never** `bizapp_credentials`, unlike the old plan) through `LakehouseCdcPublisher.publishCdcEvent(tenantId, "bizapp_records", …)` from an AFTER_COMMIT listener on `BizAppLifecycleEvent`. Views with `"engine": "lakehouse"` compile to the DuckDB dialect and run through the existing analytics path **[verify how `LakehouseAnalyticsController` reaches DuckDB; wrap it behind an `AnalyticsRuntimeApi` for `bizapp`]**; default engine stays `postgres` for read-your-writes consistency. `docs/DX_OS.md` Layer-6 text is updated only after the CDC test passes.

**K-4 SLA timers (G5).** `bizapp-single-approval.bpmn20.xml` gains reminder + escalation boundary timers driven by block `params.sla` (`PT24H` / `PT48H` defaults, capped at 14 days like `workflow_sla_configs`), reusing `WorkflowReminderDelegate` / `WorkflowTimerEscalationDelegate` patterns through `WorkflowRuntimeApi` (no cross-module internals). Header added (L-4).

**K-5 Vault consolidation.** Move the AES-256-GCM envelope to `common/security/GeneralCredentialCipher` with `BizAppCredentialCipher` and the notification `CredentialCipher` delegating — **only if** the two envelopes are byte-compatible **[verify]**; otherwise keep two ciphers and delete this item from the release (no format break for stored notification secrets).

---

## 6. Workstream T — typed storage for platform templates

- `BizAppStorageRouter` maps `(kind, entity)` → `JsonbRecordStore` (default) or `TypedRecordStore`. Routing is decided by the **platform-template registry**, not by manifest content; a manifest cannot name a table.
- `TypedRecordStore` is a hand-written repository per bundled entity generated at build time from the manifest fields (column names from the validated identifier regex; no dynamic DDL at runtime). Flyway V16/V17 own the DDL.
- `BizAppRecordService`, RBAC, audit, hooks, webhooks, CDC and the record API are unchanged — only the store behind them switches; the `payload` DTO shape is preserved.
- Report compiler targets the typed columns (S-1), so aggregates use real column types/indexes.
- Data migration is inside V16/V17 (copy then delete JSONB rows). Rollback story: restore from backup (`scripts/ops/`); documented.
- **Org-custom apps stay on JSONB.** The upgrade path "custom app → typed" is out of scope.

**T tests:** parity suite runs the same record scenarios against both stores; migration test seeds JSONB rows, runs V16, asserts typed rows equal and JSONB rows gone; isolation test across orgs on the typed table.

---

## 7. Workstream D — knowledge projection (records → chat)

Goal: let the assistant answer questions about CRM/booking data **with citations**, without touching retrieval internals and without LLM-written SQL.

- **Why projection (and not direct graph writes):** the Kuzu graph is evidence-anchored (`PUT /internal/v1/sources/{source_id}` carries `evidence_unit_id` per entity) and chat citations are validated against **documents**. Records are not documents, so each projected record becomes a synthetic Markdown document and flows through the existing ingestion → Qdrant + Kuzu pipeline (graph extraction included), inheriting provenance, dedup and in-place update.
- **Projection unit:** entity opted in via `knowledge.project`; document name `bizapp/<kind>/<entity>/<recordId>.md`; body = title + allow-listed fields (`ref` fields rendered with the referenced record's title). Re-projection on create/update replaces vectors/graph; archive deletes the document. After-commit and **asynchronous**; a projection failure never fails the record write; state is recorded in `runtime.knowledge = { state, updatedAt }` and shown in the registry view.
- **Digest document** `bizapp/<kind>/_digest.md`: rendered from the app's declared report views (S-1, row-capped) and refreshed after changes (debounced), so aggregate questions ("how many VIP customers") are answered from a cited, deterministic source instead of RAG guessing.
- **Fail-closed access rule (validator + runtime):** projection is allowed only when `rbac.read` covers **every workspace role**; otherwise `knowledge.project` is rejected. Reason: KB visibility is workspace-level, so a stricter record RBAC would leak through chat. `pii: true` fields and credential data are never projected. **[verify]** that document visibility is exactly workspace-scoped for the KB created in K-1; if private-workspace rules differ, the rule is tightened accordingly.
- **Uploader identity:** extend published `DocumentApi` with a programmatic text-ingest call **[verify what it exposes today]**, using the acting user for record events and a per-app platform service identity for the digest.
- **Privacy:** documented in `BIZAPPS.md`; when the model adapter points at an external provider, projected text crosses that boundary (`OPEN_SOURCE.md` already says so for tenant excerpts).

**D tests:** projection create/update/archive lifecycle; fail-closed rejection (read restricted to admins + `project:true`); PII field never in the document; digest numbers equal the report view; end-to-end: create lead → ask assistant → answer cites `bizapp/crm/…` source; no retrieval-module files modified (`git diff --stat` gate in review).

---

## 8. Workstream A — AI manifest drafting

- **API:** `POST /api/v1/bizapps/drafts/generate` `{ prompt, locale, baseTemplateRef? }` → `{ draftId, manifest, validation: { ok, errors[] }, diff?, meta }`; `GET/PUT/DELETE /api/v1/bizapps/drafts[/{id}]`; `POST /api/v1/bizapps/validate` (stateless validator, used by canvas). **ORG_OWNER only**, rate-limited with the existing `app.rate-limit.*` mechanism, kill-switch `app.bizapps.ai-drafting.enabled` (default `false`).
- **Never auto-installs.** Output is untrusted data stored in `bizapp_drafts` (V14); the user reviews (canvas/diff) and calls `install`, which re-validates from scratch (S-4).
- **Inference path:** the Python service owns AI (project rule). Add a structured-output RPC (e.g. `CompleteJson`) to `proto/` + Python server + Java client **together with a fixture in `contracts/`** (CONTRIBUTING rule for wire changes). Output is constrained by `bizapp.v1.schema.json` through the model adapter's JSON-schema mode where available. **[verify]** the existing proto/adapter first; `AiInferenceApi.generate` is RAG-shaped (needs a KB) and MUST NOT be abused for this.
- **Prompt rules:** system prompt = schema + 2–3 few-shot examples taken **only from bundled (Apache-2.0, original) templates**; the user prompt is the only variable input. **No tenant records, KB content or credentials are ever placed in the prompt.** Placeholders such as `"credentialRef": "$$select"` are allowed in drafts and **rejected by install**, forcing the human to choose a real vault entry.
- **Repair loop:** up to 2 retries feeding validator error messages back; then return the best draft with `validation.errors` populated.
- **Provenance:** `meta.generator="ai"`, `meta.model=<configured id>` stored on the draft and carried into any saved template (L-5).
- **Quality gate (measured, not assumed):** offline eval set `contracts/bizapp/eval/drafting-prompts.jsonl` (≥ 20 prompts, vi + en, invented). Release target: ≥ 80 % of prompts yield a validator-clean manifest after repair on the default model. **[INFERENCE]** the default 3B model may miss this; if so the release ships drafting behind the default-off flag with the measured pass rate documented — it is not silently declared "done".

**A tests:** drafts never reach `bizapp_installations`; prompt-injection fixtures (prompt asking for secrets, SQL, `http://169.254…`, `storage: typed`) yield validator rejections; rate-limit; ORG_OWNER-only; proto/fixture parity test on both sides.

---

## 9. Workstream B — visual composer (canvas)

- **Routes:** `(admin)/admin/apps/compose/[draftId]/page.tsx` (+ "New from template", "New from AI draft").
- **Components** (`frontend/src/components/bizapp/composer/`): `ComposerCanvas` (`@xyflow/react`), `NodeEntity`, `NodeBlock`, `Palette` (block types from `contracts/bizapp/blocks/*.schema.json`), `Inspector` (forms generated from the block/field JSON Schemas, secrets selectable **only** from the vault), `ValidationPanel`, `JsonTab` (plain textarea, read/paste, no new editor dependency), `ListView` (keyboard-first alternative to the canvas for accessibility).
- **Graph semantics (honest model):** nodes = entities and blocks; edges = hooks (`entity → block`), block references (`assistant.params.block → knowledge_base`), `ref` fields (`entity → entity`), report views (`report → entity`). Layout via `@dagrejs/dagre`; positions saved in `ui.layout`. The canvas is an editor of the same JSON; hand-edited manifests remain valid.
- **Server authority:** every edit debounces `POST /bizapps/validate`; errors map to node ids. Actions: save draft, save as org template, install (ORG_OWNER).
- **i18n:** `BizApps.composer.*` in `messages/en.json` and `vi.json` (enforced by `npm run i18n:check`).
- **Accessibility:** Playwright + `@axe-core/playwright` scan of the composer; every canvas action reachable from `ListView` with the keyboard.
- **License:** only MIT dependencies (L-3); no Flowise canvas code or assets; icons from the already-used `lucide-react`.

**B tests:** vitest for graph↔manifest round-trip (manifest → nodes → manifest is identity modulo `ui.layout`), inspector schema coverage (every block type has a schema), validation-error mapping; Playwright: build CRM from the palette, validate, save draft, install.

---

## 10. Workstream F — template exchange (export / import / remix / optional catalog)

- **Envelope** `openvie.template.v1` (L-5) is used by bundled files, org templates (`V15` `meta`), export and import. `marketplace_validate.py` validates every bundled file against the envelope + `bizapp.v1` schemas and the red-team fixtures (`jsonschema` installed in the job; MIT).
- **Endpoints** (all `/api/v1`, ORG_OWNER unless noted):

| Method & path | Purpose |
| --- | --- |
| `GET /marketplace/bizapps/{id}/export` (member for platform, owner for org) | download envelope JSON |
| `POST /marketplace/bizapps/import` (multipart JSON or `{ url, sha256 }`) | validate → **draft only**, never auto-install |
| `POST /marketplace/bizapps/{id}/remix` | copy to draft, retain upstream `meta`, append `changes[]` |
| `GET /marketplace/catalog` | entries of the configured remote catalog index |

- **Remote catalog (default off):** `app.bizapps.remote-catalog.enabled=false`, `…hosts=[]` allow-list, HTTPS only, uses `SafeOutboundHttp` (S-2), body ≤ 256 KiB, **SHA-256 from the catalog index must match**, SPDX allow-list enforced (L-5). The `OpenVieTemplates` repository itself is out of scope here; the plan delivers the client, the index format (`index.json`: `[{ id, name, version, license, url, sha256 }]`) and a docs page describing the repo layout.
- **Frontend:** import/export/remix actions in `(admin)/admin/apps`; license + author badges on template cards; a remix shows the retained attribution.

**F tests:** hash mismatch rejected; non-allow-listed SPDX rejected; remix keeps upstream author and appends a change; import lands in drafts only; SSRF fixtures for the URL path; export→import round-trip equality.

---

## 11. Workstream E — e-signature through `external_call` (no legal exposure)

Decision (user, 2026-11-02): signing is **delegated to an external provider the organization contracts with**. OpenVie stores no signing keys, runs no signing/verification code, and states nothing about legal validity.

- **E-1 Hardened `external_call`** (S-2) plus new optional params, all validated:
  - `bodyTemplate`: JSON with placeholders `{{record.<field>}}`, `{{record.id}}`, `{{app.kind}}` only (allow-list; no expressions; values JSON-escaped). Replaces the "`${…}` is forbidden" ambiguity: `{{…}}` is the single, closed substitution syntax.
  - `responseMap`: `{ "envelopeId": "/id", "status": "/status" }` — JSON-pointer extraction (≤ 8 keys, values ≤ 200 chars) stored in `runtime.external.<blockId>` and shown in the registry view.
  - `X-Idempotency-Key: <recordId>:<blockId>:<version>` header (a provider that honors it will not double-send).
  - Hook target stays the existing `onCreate | onUpdate | onDecide`; an approval decision can fire the call.
- **E-2 Bundled recipe template** `marketplace/bizapps/signature-request.json` (original content): entity `signatureRequest` (`title`, `signerEmail`, `documentRef`, `signatureStatus` enum `PENDING|SIGNED|DECLINED|EXPIRED`), `approval_workitem` → on `APPROVE` call the provider via `external_call`. Provider URL/host are **installation config** (`config.provider.url`, `config.allowedHosts`) — the block is inert until the org sets them. No vendor names in the template (L-1 §6).
- **E-3 Vault:** provider key stored as `api_key` / `bearer` entry; PEM private keys rejected (S-3).
- **E-4 Status write-back:** the provider (or the org's middleware) updates `signatureStatus` by calling the existing `PATCH /api/v1/bizapp-data/{kind}/{entity}/{id}` with an `ovk_…` API key (service member, subject to manifest `rbac.write`). No new inbound endpoint in v1.0.0.
- **E-5 Docs — "Legal boundaries" (vi + en):** OpenVie does not create, hold keys for, or validate digital signatures; it forwards a request to a provider chosen by the operator; legal effect depends on that provider and applicable law — operators must obtain their own legal advice. (Statute citations are intentionally omitted until counsel confirms them.)

**E tests:** placeholder allow-list (unknown `{{x}}` → reject); response map extracts and caps values; idempotency header present and stable; inert without config; SSRF fixtures; recipe template validates in CI; a PEM-key credential is refused.

---

## 12. API surface summary (new/changed; all under `/api/v1`, no APISIX change)

| Path | Role | Workstream |
| --- | --- | --- |
| `POST /bizapps/validate` | ORG_OWNER | A/B |
| `POST /bizapps/drafts/generate` · `GET/PUT/DELETE /bizapps/drafts[/{id}]` | ORG_OWNER | A/B/F |
| `POST /apps/install` (accepts `templateRef` **or** `draftId`) | ORG_OWNER | A/B/F |
| `GET /marketplace/bizapps/{id}/export` · `POST …/import` · `POST …/{id}/remix` · `GET /marketplace/catalog` | see §10 | F |
| `PATCH /bizapps/{kind}/config` (adds `allowedHosts`, `provider.*`) | ORG_OWNER | S/E |
| `POST /bizapps/{kind}/reports/{view}` (declarative views; `engine` per view) | per manifest | S/K |
| assistant endpoints | via the existing chat routes, KB-scoped | K |

Existing routes in `docs/BIZAPPS.md` keep their paths. Property keys added: `app.bizapps.outbound.allow-http`, `app.bizapps.remote-catalog.{enabled,hosts}`, `app.bizapps.ai-drafting.enabled` (all documented in `.env.example`).

---

## 13. Tests, CI and observability

**CI jobs after this release**

| Job | Purpose |
| --- | --- |
| `license-headers` | extended coverage (L-4) |
| `dependency-licenses` | Java + Node + Python allow-list (L-1) — new |
| `artifact-license` | `LICENSE`/`NOTICE` present in jar and image contexts (§4(a)) — new |
| `marketplace-validate` | envelope + `bizapp.v1` schema + red-team fixtures |
| `java` (`make verify`) | unit + MockMvc + ArchUnit (new `chat`, `analytics` published `*Api` respected) |
| `frontend` / `e2e` | lint, typecheck, `i18n:check`, vitest, build, Playwright + axe (composer) |
| `python` | proto/fixture parity for the drafting RPC |

**Observability:** Micrometer `bizapp_executions_total{block,outcome}` (no tenant/app cardinality explosion — org/app ids are **not** metric labels, correcting the old plan) and `bizapp_executions_seconds`; counters for `bizapp_draft_validation_total{result}` and `bizapp_projection_total{state}`. Audit actions added: `BIZAPP_DRAFT_CREATED/GENERATED/DELETED`, `BIZAPP_TEMPLATE_IMPORTED/EXPORTED/REMIXED`, `BIZAPP_EXTERNAL_CALL`, `BIZAPP_KB_PROJECTED`.

---

## 14. Docs, SemVer freeze & v1.0.0 release chores (workstream R)

1. `docs/BIZAPPS.md` (vi + en): frozen `bizapp.v1` reference, declarative reports, typed vs JSONB storage, knowledge projection and its access rule, AI drafting (draft-only), composer, template exchange + license metadata, e-signature recipe and **Legal boundaries**, non-goals (§0.4).
2. `docs/DX_OS.md` (both languages): update the Tier-3 row (`Achieved — v1.0.0`), Layer-3/4/6 notes, and the table count (computed, §2). Do not claim DuckDB reports until K-3's test passes.
3. `docs/ARCHITECTURE.md`, `docs/INTEGRATION.md` (`bizapp.*` events unchanged; add drafting/import endpoints and SemVer 1.0 stability guarantee on `/api/v1/**`), `docs/DEPLOYMENT.md` (new properties, `BIZAPP_ENC_KEY` rotation note, `1.0.0` upgrade/fresh-install check), `docs/OPEN_SOURCE.md` + `CONTRIBUTING.md` + `NOTICE` (L-2, L-7, plus walk the 4-item Publication Gate in `OPEN_SOURCE.md` §"Publication gate").
4. Unified `1.0.0` version & release notes:
   - `api/pom.xml` `<version>` → `1.0.0` (populates OpenAPI `@project.version@`);
   - `rag-chatbot-fastapi/pyproject.toml` `version` → `"1.0.0"` (`OpenVie Graph Service` / FastAPI app version aligned to `1.0.0`);
   - `frontend/package.json` `"version"` → `"1.0.0"`;
   - `README.md` roadmap (both languages): rename "Lộ trình MVP / MVP roadmap" or add the culminating `v1.0.0` row covering full 3-tier DX-OS completion directly after `v0.3.x` (no `v0.4.0` / `v0.5.0` rows);
   - `CHANGELOG.md`: cut `## [1.0.0] - YYYY-MM-DD` consolidating all Tier-3 BizApp capabilities, security hardening, authoring, knowledge projection, typed storage, and Apache-2.0 compliance gates since `0.3.2`.
5. `docs/README.md` index: verify BizApps row and 1.0 references read consistently in both languages.

---

## 15. Milestones, order and definition of done

Order is dependency-driven: **L and S first** (everything else inherits their gates), then K and T, then D, then A/B/F in parallel, then E, then R.

| # | Milestone | Depends on | Est. (dev-days, rough) |
| --- | --- | --- | --- |
| M0 | L: gates, NOTICE, headers, package metadata, vendored-file register | — | 2 |
| M1 | S: frozen `bizapp.v1` schema, report compiler, `SafeOutboundHttp`, vault rules, red-team suite | M0 | 4 |
| M2 | K: `knowledge_base` + `assistant` blocks + `AssistantView`; CDC + lakehouse engine; SLA timers; cipher consolidation (conditional) | M1 | 4 |
| M3 | T: storage router, V16/V17 typed tables + data move, parity tests | M1 | 3 |
| M4 | D: projection + digest + fail-closed rule | M2, M3 | 4 |
| M5 | A: drafts table, validate/generate API, drafting RPC + eval set | M1 | 4 |
| M6 | B: composer (needs drafts + validate) | M5 (API), M1 | 5 |
| M7 | F: envelope, export/import/remix, optional catalog client | M1, M5 | 3 |
| M8 | E: `external_call` extensions + recipe template | M1 | 2 |
| M9 | R: unified `1.0.0` versions, SemVer contract freeze, docs, DX_OS, README, CHANGELOG, publication gate, final full CI | all | 2 |

Total ≈ **33 dev-days** (rough estimate, not a commitment; M5–M8 can run in parallel after M1).

### Definition of done — all must pass

1. `dependency-licenses`, `artifact-license`, `license-headers` (extended), `marketplace-validate` and all existing jobs green; `NOTICE` matches the generated inventory.
2. **Isolation:** red-team suite green; org B gets 404 for org A's apps, records, reports, drafts, templates; no raw SQL accepted anywhere; no SSRF fixture succeeds.
3. Fresh clone → setup → install CRM (typed storage) from the marketplace with one `POST`; create a lead → submit → decide through the generic inbox → signed `bizapp.crm.record.decided` webhook received; reminder timer present in the deployed process.
4. Assistant block answers a question about a created lead **with a citation to the projected document**; an aggregate question is answered from the digest; the retrieval module has no diff.
5. Report view returns identical results on `postgres` and `lakehouse` engines for the same data (K-3 test).
6. Credential deletion → dependent `external_call` reports "credential unavailable" with no secret in logs/audit/CDC; PEM private key refused.
7. AI drafting: draft never installs; eval pass rate measured and recorded in `BIZAPPS.md` (flag default off if below target).
8. Composer: build, validate, save and install an app from the canvas; axe scan clean; `i18n:check` green.
9. Template exchange: export → import → remix round-trip keeps author/license and appends `changes[]`; hash mismatch and forbidden SPDX rejected.
10. e-Signature recipe installs, is inert without provider config, calls an HTTPS provider stub through the hardened path, and stores the mapped response; docs carry the Legal boundaries section in both languages.
11. Unified `1.0.0` version across `api/pom.xml`, `rag-chatbot-fastapi/pyproject.toml`, and `frontend/package.json`; `docs/DX_OS.md` Tier-3 row (`Achieved — v1.0.0`), `README.md` roadmap, and `CHANGELOG.md` `[1.0.0]` describe v1.0.0 accurately with zero `v0.4`/`v0.5` release references and no capability claimed that a test above does not prove.
