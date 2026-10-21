package com.openvie.api.document.api;

/**
 * Approval state of a document, written back by the workflow module when an
 * approval process opens and when it settles.
 *
 * <p>It lives in the module's public contract because the workflow module owns
 * the process that produces it; this is deliberately separate from the
 * ingestion state ({@code DocumentStatus}), which is an independent axis — a
 * document can be fully indexed and still be waiting for review.
 *
 * <p>{@code null} on the document row means "never submitted", which the API
 * surfaces as an absent value rather than a fourth constant.
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}