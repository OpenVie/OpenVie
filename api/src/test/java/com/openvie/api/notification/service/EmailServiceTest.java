package com.openvie.api.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmailServiceTest {

    private EmailProvider provider;
    private NotificationChannelResolver resolver;
    private UUID orgId;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        provider = mock(EmailProvider.class);
        when(provider.type()).thenReturn("smtp");
        when(provider.providerName()).thenReturn("SMTP");
        when(provider.available(any())).thenReturn(true);
        resolver = mock(NotificationChannelResolver.class);
        orgId = UUID.randomUUID();
        emailService = new EmailService(resolver, "http://localhost:3000/accept-invitation",
                "http://localhost:3000/login", "");
    }

    @Test
    void sendsThroughTheResolvedChannel() {
        givenResolved(provider, config());

        emailService.sendInvitationEmail(orgId, "user@example.com", "Acme", "Finance", "MEMBER",
                "invite-token", LocalDateTime.now().plusHours(72));

        ArgumentCaptor<EmailMessage> messageCaptor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(provider).send(messageCaptor.capture(), any());
        EmailMessage message = messageCaptor.getValue();
        assertEquals("user@example.com", message.toEmail());
        assertEquals("You're invited to join Finance on OpenVie", message.subject());
    }

    @Test
    void invitationLinksToAcceptanceWithTokenAndNamesBothLevels() {
        givenResolved(provider, config());

        emailService.sendInvitationEmail(orgId, "user@example.com", "Acme", "Finance",
                "WORKSPACE_ADMIN", "invite-token", LocalDateTime.of(2026, 10, 4, 12, 0));

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
        givenResolved(provider, config());

        emailService.sendInvitationEmail(orgId, "user@example.com", "Acme", "Finance", "MEMBER",
                "t", LocalDateTime.now().plusHours(72));

        assertTrue(captured().htmlContent().contains("as a <strong>member</strong>"));
    }

    @Test
    void missingChannelFailsLoudly() {
        when(resolver.resolve(orgId)).thenReturn(Optional.empty());

        EmailDeliveryException exception = assertThrows(EmailDeliveryException.class,
                () -> emailService.sendInvitationEmail(orgId, "user@example.com", "Acme",
                        "Finance", "MEMBER", "t", LocalDateTime.now().plusHours(72)));
        assertTrue(exception.getMessage().contains("no enabled notification channel"),
                "the failure must name the missing precondition: " + exception.getMessage());
    }

    @Test
    void unusableProviderFailsBeforeSend() {
        EmailProvider broken = mock(EmailProvider.class);
        when(broken.providerName()).thenReturn("SMTP");
        when(broken.available(any())).thenReturn(false);
        givenResolved(broken, config());

        assertThrows(EmailDeliveryException.class,
                () -> emailService.sendInvitationEmail(orgId, "user@example.com", "Acme",
                        "Finance", "MEMBER", "t", LocalDateTime.now().plusHours(72)));
        verify(provider, never()).send(any(), any());
    }

    @Test
    void everyEmailCarriesTheDarkBrandShellAndLogo() {
        givenResolved(provider, config());

        emailService.sendAccountCreatedEmail(orgId, "user@example.com", "Ada Lovelace");

        String html = captured().htmlContent();
        assertTrue(html.contains("background:#07090c"),
                "the email body must use the app's dark background: " + html);
        assertTrue(html.contains("background:#c8f53c"),
                "the button must use the lime accent");
        assertTrue(html.contains("<img class=\"logo\" src=\"http://localhost:3000/logo.png\""),
                "the logo must be the derived PNG from the login origin");
    }

    @Test
    void workspaceAddedEmailUsesTheDarkShellAndLogo() {
        givenResolved(provider, config());

        emailService.sendWorkspaceAddedEmail(orgId, "user@example.com", "Ada Lovelace",
                "Acme", "Finance", "MEMBER");

        String html = captured().htmlContent();
        assertTrue(html.contains("<img class=\"logo\" src=\"http://localhost:3000/logo.png\""));
        assertTrue(html.contains("Finance"));
    }

    @Test
    void logoUrlIsDerivedOrConfigurable() {
        // Unset config derives /logo.png from the login link's origin...
        assertEquals("http://localhost:3000/logo.png",
                EmailService.resolveLogoUrl("", "http://localhost:3000/login"));
        // ...is robust against a malformed login link...
        assertEquals("http://localhost:3000/logo.png",
                EmailService.resolveLogoUrl(null, "not-a-uri"));
        // ...and an explicitly configured URL always wins (trimmed).
        assertEquals("https://cdn.example.com/brand.png",
                EmailService.resolveLogoUrl(" https://cdn.example.com/brand.png ",
                        "http://localhost:3000/login"));
    }

    private void givenResolved(EmailProvider target, NotificationChannelConfig settings) {
        when(resolver.resolve(orgId)).thenReturn(Optional.of(
                new NotificationChannelResolver.ResolvedChannel(target, settings)));
    }

    private NotificationChannelConfig config() {
        return new NotificationChannelConfig(
                "smtp", "from@example.com", "OpenVie", "", 587, "", "",
                false, false);
    }

    private EmailMessage captured() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(provider).send(captor.capture(), any());
        return captor.getValue();
    }
}
