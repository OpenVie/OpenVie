# ADR-0002: Approval Decision Write-Back and Notification Contract

- **Status:** Accepted
- **Date:** 2026-10-21
- **Context:** Issue #29 / #30 / #34 / #35 (DX-OS Open-Core OLP 2026)

## Context

OpenVie embeds Flowable 8 BPMN engine for two-level document approval (`eofficeDocumentApproval`). In the initial implementation (workflow runtime MVP), the engine managed process state and recorded audit logs, but the decision outcome was only stored as a process variable (`documentStatus = "APPROVED"|"REJECTED"`). The document record in `documents` remained unaware of the review process.

Consequently:
1. Document lists and status checks had to traverse engine history or rely on frontend journal-derived mapping.
2. Ingestion pipeline and external integrators had no authoritative database column on `documents` to gate publication or downstream distribution.
3. Submitting users received no automated notification upon verdict arrival.

## Decision

We establish an authoritative write-back contract between the workflow module and the document module:

1. **Independent Orthogonal Axes:**
   - `documents.status` (`DocumentStatus`): Tracks the ingestion pipeline lifecycle (`PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`).
   - `documents.approval_status` (`ApprovalStatus`): Tracks the governance/review lifecycle (`NULL` = never submitted, `PENDING` = open review, `APPROVED`, `REJECTED`).
   - Stored in Flyway migration `V8__document_approval_writeback.sql`.

2. **Write-Back Seam (`DocumentApi`):**
   - At process initiation (`WorkflowService.start`), `DocumentApi.markApprovalPending(tenantId, documentId, publishTarget)` sets `approval_status = 'PENDING'` and `approval_target`.
   - At process settlement (`WorkflowService.complete` or the auto-approve short-circuit in `start`), `DocumentApi.recordApprovalDecision(tenantId, documentId, decision, decidedBy)` commits the final verdict and timestamp.
   - Flowable remains the immutable audit journal; the `documents` row provides $O(1)$ queryable truth for listings, caches, and access control.

3. **Notification Decoupling:**
   - Workflow publishes a domain event `WorkflowDecisionEvent` (`com.openvie.api.workflow.api.event`) upon terminal outcome.
   - `NotificationListener` consumes the event post-commit (`@TransactionalEventListener(phase = AFTER_COMMIT)`) and records a `Notification` row of type `WORKFLOW_DECIDED`, then attempts delivery via `EmailService.sendWorkflowDecisionEmail`.
   - Notification delivery is best-effort: failure to deliver email never rolls back the committed approval verdict.

4. **Publication Handoff:**
   - Integrators are notified of document publication readiness via transactional webhooks (`workflow.approved` and `document.status.changed`).
   - The document row's `approval_target` (`workspace`, `shared`, `public`) dictates the audience scope for downstream distribution.

## Consequences

- **Positives:**
  - Zero cross-module cyclic dependencies: `workflow` depends only on `document.api` and `common.event`.
  - Clean schema evolution with full backward compatibility (existing documents have `approval_status = NULL`).
  - Cache invalidation triggers automatically when approval status mutates, ensuring cache coherency across API nodes.
- **Trade-offs:**
  - External publication pipelines (e.g., cross-tenant syndication) must poll or hook into the `workflow.approved` webhook event.
