package com.cacanode.api.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmailServiceTest {

    private EmailProvider sendGridProvider;
    private EmailProvider brevoProvider;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        sendGridProvider = mock(EmailProvider.class);
        brevoProvider = mock(EmailProvider.class);

        when(sendGridProvider.providerName()).thenReturn("SendGrid");
        when(brevoProvider.providerName()).thenReturn("Brevo");

        emailService = new EmailService(
                sendGridProvider,
                brevoProvider,
                "http://localhost:3000/accept-invitation"
        );
    }

    @Test
    void sendGridSuccessDoesNotCallBrevo() {
        emailService.sendInvitationEmail("user@example.com", "Acme", "Finance", "MEMBER",
                "invite-token", LocalDateTime.now().plusHours(72));

        ArgumentCaptor<EmailMessage> messageCaptor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(sendGridProvider).send(messageCaptor.capture());
        verify(brevoProvider, never()).send(any());

        EmailMessage message = messageCaptor.getValue();
        assertEquals("user@example.com", message.toEmail());
        assertEquals("You're invited to join Finance on OpenVie", message.subject());
    }

    @Test
    void invitationLinksToAcceptanceWithTokenAndNamesBothLevels() {
        emailService.sendInvitationEmail("user@example.com", "Acme", "Finance", "WORKSPACE_ADMIN",
                "invite-token", LocalDateTime.of(2026, 10, 4, 12, 0));

        EmailMessage message = captured();
        assertTrue(message.htmlContent().contains(
                "http://localhost:3000/accept-invitation?token=invite-token"));
        // The invitee needs to see which organization and which workspace.
        assertTrue(message.htmlContent().contains("Acme"));
        assertTrue(message.htmlContent().contains("Finance"));
        assertTrue(message.htmlContent().contains("workspace admin"));
    }

    @Test
    void memberRoleIsNotLabeledAsAdmin() {
        emailService.sendInvitationEmail("user@example.com", "Acme", "Finance", "MEMBER",
                "t", LocalDateTime.now().plusHours(72));

        assertTrue(captured().htmlContent().contains("as a <strong>member</strong>"));
    }

    @Test
    void sendGridFailureThenBrevoSuccessDoesNotThrow() {
        doThrow(new EmailDeliveryException("sendgrid down"))
                .when(sendGridProvider)
                .send(any(EmailMessage.class));

        emailService.sendInvitationEmail("user@example.com", "Acme", "Finance", "MEMBER",
                "t", LocalDateTime.now().plusHours(72));

        verify(sendGridProvider).send(any(EmailMessage.class));
        verify(brevoProvider).send(any(EmailMessage.class));
    }

    @Test
    void bothProvidersFailThrowsDeliveryException() {
        doThrow(new EmailDeliveryException("sendgrid down"))
                .when(sendGridProvider)
                .send(any(EmailMessage.class));
        doThrow(new EmailDeliveryException("brevo down"))
                .when(brevoProvider)
                .send(any(EmailMessage.class));

        assertThrows(EmailDeliveryException.class,
                () -> emailService.sendInvitationEmail("user@example.com", "Acme", "Finance",
                        "MEMBER", "t", LocalDateTime.now().plusHours(72)));
    }

    @Test
    void noLoginOrWelcomeTemplateRemains() {
        // Email is optional and login is password-only: only the invitation
        // template may exist.
        assertFalse(hasMethod("sendWelcomeEmail"));
        assertFalse(hasMethod("sendLogin2FAEmail"));
        assertFalse(hasMethod("sendLogin2FACodeEmail"));
    }

    private EmailMessage captured() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(sendGridProvider).send(captor.capture());
        return captor.getValue();
    }

    private boolean hasMethod(String name) {
        return java.util.Arrays.stream(EmailService.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals(name));
    }
}
