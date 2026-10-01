package com.cacanode.api.notification.listener;

import com.cacanode.api.auth.api.event.Login2FARequestedEvent;
import com.cacanode.api.auth.api.event.UserRegisteredEvent;
import com.cacanode.api.tenant.api.event.UserInvitedEvent;
import com.cacanode.api.notification.service.NotificationService;
import com.cacanode.api.common.event.durable.ModuleEventInboxService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j(topic = "NOTIFICATION-LISTENER")
@Component
@RequiredArgsConstructor
public class NotificationListener {

    private final NotificationService notificationService;
    @Autowired(required = false)
    private ModuleEventInboxService inboxService;

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleUserRegistered(UserRegisteredEvent event) {
        if (!claim("notification.welcome-email")) return;
        log.info("Sending confirmation email to: {}", event.email());
        try {
            notificationService.sendAndRecordWelcomeEmail(
                    event.tenantId(),
                    event.userId(),
                    event.email(),
                    event.fullName(),
                    event.companyName(),
                    event.verificationToken());
        } catch (Exception e) {
            log.error("Failed to send welcome email to {}: {}", event.email(), e.getMessage());
            throw new IllegalStateException("Welcome email delivery failed", e);
        }
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleLogin2FARequested(Login2FARequestedEvent event) {
        if (!claim("notification.login-2fa-email")) return;
        log.info("Sending login 2FA email to: {}", event.email());
        try {
            notificationService.sendAndRecordLogin2FAEmail(
                    event.tenantId(),
                    event.userId(),
                    event.email(),
                    event.fullName(),
                    event.verificationSecret(),
                    event.challengeType());
        } catch (Exception e) {
            log.error("Failed to send login 2FA email to {}: {}", event.email(), e.getMessage());
            throw new IllegalStateException("Login 2FA email delivery failed", e);
        }
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleUserInvited(UserInvitedEvent event) {
        if (!claim("notification.invitation-email")) return;
        log.info("Sending invitation email to: {}", event.email());
        try {
            notificationService.sendAndRecordInvitationEmail(
                    event.tenantId(), event.email(), event.tenantName(), event.role(),
                    event.token(), event.expiresAt());
        } catch (Exception e) {
            log.error("Failed to send invitation email to {}: {}", event.email(), e.getMessage());
            throw new IllegalStateException("Invitation email delivery failed", e);
        }
    }

    private boolean claim(String consumerName) {
        return inboxService == null || inboxService.claim(consumerName);
    }

}
