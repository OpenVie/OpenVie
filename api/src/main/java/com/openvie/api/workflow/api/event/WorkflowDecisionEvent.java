package com.openvie.api.workflow.api.event;

import java.util.UUID;

/**
 * Published by the workflow module the moment an approval process settles, so
 * the notification module can tell the submitter without the workflow module
 * ever reaching into another module's internals.
 *
 * <p>It carries the recipient's resolved address so the consumer needs no
 * lookup of its own; a {@code null} {@code recipientEmail} means the account no
 * longer resolves and the notice is skipped.
 *
 * <p>{@code decision} is the terminal verdict only — {@code APPROVE} or
 * {@code REJECT}; a hand-off to level 2 is not a decision and is reported
 * through the audit trail instead.
 */
public record WorkflowDecisionEvent(
        UUID organizationId,
        UUID workspaceId,
        UUID processInstanceId,
        UUID documentId,
        String documentFileName,
        UUID recipientUserId,
        String recipientEmail,
        String recipientName,
        String decision,
        String reason,
        int approvalLevel,
        String publishTarget) {
}
