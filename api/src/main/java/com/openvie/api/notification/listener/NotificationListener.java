package com.openvie.api.notification.listener;

import com.openvie.api.common.event.durable.ModuleEventInboxService;
import com.openvie.api.notification.service.EmailService;
import com.openvie.api.notification.service.NotificationService;
import com.openvie.api.tenant.api.event.AccountCreatedEvent;
import com.openvie.api.tenant.api.event.UserInvitedEvent;
import com.openvie.api.tenant.api.event.WorkspaceMemberAddedEvent;
import com.openvie.api.workflow.api.event.WorkflowDecisionEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers the transactional emails this product sends: the workspace
 * invitation and the best-effort membership and account notices.
 *
 * <p>The invitation handler runs on the durable outbox replay: the relay
 * already gated the event on an enabled channel, so a delivery failure there
 * is a real transport error and dead-letters normally. The member-added and
 * account-created handlers are fired from the publishing transaction only
 * after it commits, and delivery failures never propagate — EmailService
 * logs skipped and failed notices itself.
 */
@Slf4j(topic = "NOTIFICATION-LISTENER")
@Component
@RequiredArgsConstructor
public class NotificationListener {

    private final NotificationService notificationService;
    private final EmailService emailService;
    @Autowired(required = false)
    private ModuleEventInboxService inboxService;

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleUserInvited(UserInvitedEvent event) {
        if (!claim("notification.invitation-email")) return;
        log.info("Sending invitation email to: {}", event.email());
        try {
            notificationService.sendAndRecordInvitationEmail(
                    event.orgId(), event.tenantId(), event.email(), event.organizationName(),
                    event.workspaceName(), event.role(), event.token(), event.expiresAt());
        } catch (Exception e) {
            log.error("Failed to send invitation email to {}: {}", event.email(), e.getMessage());
            throw new IllegalStateException("Invitation email delivery failed", e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWorkspaceMemberAdded(WorkspaceMemberAddedEvent event) {
        log.info("Sending workspace-added notice to: {}", event.email());
        try {
            emailService.sendWorkspaceAddedEmail(event.orgId(), event.email(), event.fullName(),
                    event.organizationName(), event.workspaceName(), event.role());
        } catch (Exception e) {
            log.warn("Workspace-added notice to {} failed: {}", event.email(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAccountCreated(AccountCreatedEvent event) {
        log.info("Sending account-created notice to: {}", event.email());
        try {
            emailService.sendAccountCreatedEmail(event.orgId(), event.email(), event.fullName());
        } catch (Exception e) {
            log.warn("Account-created notice to {} failed: {}", event.email(), e.getMessage());
        }
    }

    /**
     * Submitter-facing verdict notice for a settled approval process. Fired
     * after the workflow transaction commits, so a mail failure only marks the
     * notification journal row FAILED.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleWorkflowDecision(WorkflowDecisionEvent event) {
        log.info("Sending workflow {} notice to: {}", event.decision(), event.recipientUserId());
        try {
            notificationService.sendAndRecordWorkflowDecisionEmail(
                    event.organizationId(), event.workspaceId(), event.recipientUserId(),
                    event.recipientEmail(), event.recipientName(), event.documentFileName(),
                    event.decision(), event.reason(), event.approvalLevel(), event.publishTarget());
        } catch (Exception e) {
            log.warn("Workflow decision notice to {} failed: {}",
                    event.recipientUserId(), e.getMessage());
        }
    }

    private boolean claim(String consumerName) {
        return inboxService == null || inboxService.claim(consumerName);
    }

}
