package com.cacanode.api.notification.listener;

import com.cacanode.api.common.event.durable.ModuleEventInboxService;
import com.cacanode.api.notification.service.NotificationService;
import com.cacanode.api.tenant.api.event.UserInvitedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers the only email this product sends: a workspace invitation. Login
 * verification and welcome mail are gone because email is optional and login
 * no longer issues a challenge.
 */
@Slf4j(topic = "NOTIFICATION-LISTENER")
@Component
@RequiredArgsConstructor
public class NotificationListener {

    private final NotificationService notificationService;
    @Autowired(required = false)
    private ModuleEventInboxService inboxService;

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleUserInvited(UserInvitedEvent event) {
        if (!claim("notification.invitation-email")) return;
        log.info("Sending invitation email to: {}", event.email());
        try {
            notificationService.sendAndRecordInvitationEmail(
                    event.tenantId(), event.email(), event.organizationName(),
                    event.workspaceName(), event.role(), event.token(), event.expiresAt());
        } catch (Exception e) {
            log.error("Failed to send invitation email to {}: {}", event.email(), e.getMessage());
            throw new IllegalStateException("Invitation email delivery failed", e);
        }
    }

    private boolean claim(String consumerName) {
        return inboxService == null || inboxService.claim(consumerName);
    }

}
