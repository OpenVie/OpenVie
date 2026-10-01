package com.cacanode.api.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cacanode.api.notification.model.NotificationChannel;
import com.cacanode.api.notification.repository.NotificationChannelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Channel precedence: an enabled stored channel beats the environment; the
 * environment is the bootstrap default; nothing configured means no mail.
 */
class NotificationChannelResolverTest {

    private NotificationChannelRepository channelRepository;
    private CredentialCipher cipher;
    private EmailProvider smtp;
    private NotificationChannelResolver resolver;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        channelRepository = mock(NotificationChannelRepository.class);
        cipher = mock(CredentialCipher.class);
        smtp = mock(EmailProvider.class);
        when(smtp.type()).thenReturn("smtp");
        ObjectMapper objectMapper = new ObjectMapper();

        resolver = new NotificationChannelResolver(channelRepository, cipher, objectMapper,
                List.of(smtp));
        ReflectionTestUtils.setField(resolver, "environmentProvider", "none");
        ReflectionTestUtils.setField(resolver, "environmentFromEmail", "");
        ReflectionTestUtils.setField(resolver, "environmentFromName", "OpenVie");
        ReflectionTestUtils.setField(resolver, "smtpHost", "");
        ReflectionTestUtils.setField(resolver, "smtpPort", 587);
        ReflectionTestUtils.setField(resolver, "smtpUsername", "");
        ReflectionTestUtils.setField(resolver, "smtpPassword", "");
        ReflectionTestUtils.setField(resolver, "smtpAuth", false);
        ReflectionTestUtils.setField(resolver, "smtpStarttls", false);
        ReflectionTestUtils.setField(resolver, "sendgridApiKey", "");
        ReflectionTestUtils.setField(resolver, "brevoApiKey", "");
        orgId = UUID.randomUUID();
    }

    @Test
    void noChannelAndNoEnvironmentMeansNoMail() {
        when(channelRepository.findByOrgIdOrderByCreatedAtAsc(orgId)).thenReturn(List.of());

        assertTrue(resolver.resolve(orgId).isEmpty());
    }

    @Test
    void environmentActsAsBootstrapDefault() {
        when(channelRepository.findByOrgIdOrderByCreatedAtAsc(orgId)).thenReturn(List.of());
        ReflectionTestUtils.setField(resolver, "environmentProvider", "smtp");
        ReflectionTestUtils.setField(resolver, "environmentFromEmail", "boot@example.com");
        ReflectionTestUtils.setField(resolver, "smtpHost", "relay.local");

        NotificationChannelResolver.ResolvedChannel resolved =
                resolver.resolve(orgId).orElseThrow();

        assertEquals(smtp, resolved.provider());
        assertEquals("boot@example.com", resolved.config().fromEmail());
        assertEquals("relay.local", resolved.config().host());
    }

    @Test
    void enabledStoredChannelBeatsTheEnvironment() {
        NotificationChannel channel = storedChannel("smtp", true);
        when(channelRepository.findByOrgIdOrderByCreatedAtAsc(orgId))
                .thenReturn(List.of(channel));
        when(cipher.available()).thenReturn(true);
        when(cipher.decrypt("ciphertext")).thenReturn(
                "{\"host\":\"stored.relay\",\"port\":2525,\"fromEmail\":\"stored@example.com\"}");
        ReflectionTestUtils.setField(resolver, "environmentProvider", "smtp");
        ReflectionTestUtils.setField(resolver, "environmentFromEmail", "boot@example.com");
        ReflectionTestUtils.setField(resolver, "smtpHost", "env.relay");

        NotificationChannelResolver.ResolvedChannel resolved =
                resolver.resolve(orgId).orElseThrow();

        assertEquals("stored.relay", resolved.config().host());
        assertEquals(2525, resolved.config().port());
        assertEquals("stored@example.com", resolved.config().fromEmail());
    }

    @Test
    void disabledStoredChannelFallsBackToEnvironment() {
        NotificationChannel channel = storedChannel("smtp", false);
        when(channelRepository.findByOrgIdOrderByCreatedAtAsc(orgId))
                .thenReturn(List.of(channel));
        ReflectionTestUtils.setField(resolver, "environmentProvider", "smtp");
        ReflectionTestUtils.setField(resolver, "environmentFromEmail", "boot@example.com");
        ReflectionTestUtils.setField(resolver, "smtpHost", "env.relay");

        NotificationChannelResolver.ResolvedChannel resolved =
                resolver.resolve(orgId).orElseThrow();

        assertEquals("env.relay", resolved.config().host());
    }

    @Test
    void unknownStoredTypeFailsWithActionableMessage() {
        NotificationChannel channel = storedChannel("carrier-pigeon", true);
        when(channelRepository.findByOrgIdOrderByCreatedAtAsc(orgId))
                .thenReturn(List.of(channel));

        EmailDeliveryException exception = assertThrows(EmailDeliveryException.class,
                () -> resolver.resolve(orgId));
        assertTrue(exception.getMessage().contains("carrier-pigeon"),
                "must name the unknown type: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("smtp"),
                "must list available types: " + exception.getMessage());
    }

    @Test
    void storedChannelWithoutEncryptionKeyFailsLoudly() {
        NotificationChannel channel = storedChannel("smtp", true);
        when(channelRepository.findByOrgIdOrderByCreatedAtAsc(orgId))
                .thenReturn(List.of(channel));
        when(cipher.available()).thenReturn(false);

        EmailDeliveryException exception = assertThrows(EmailDeliveryException.class,
                () -> resolver.resolve(orgId));
        assertTrue(exception.getMessage().contains("NOTIFICATION_ENC_KEY"),
                "must name the missing variable: " + exception.getMessage());
    }

    @Test
    void environmentProviderWithoutFromEmailIsNotUsable() {
        when(channelRepository.findByOrgIdOrderByCreatedAtAsc(orgId)).thenReturn(List.of());
        ReflectionTestUtils.setField(resolver, "environmentProvider", "smtp");
        ReflectionTestUtils.setField(resolver, "environmentFromEmail", "");

        assertTrue(resolver.resolve(orgId).isEmpty());
        assertFalse(resolver.environmentDefault().isPresent());
    }

    private NotificationChannel storedChannel(String type, boolean enabled) {
        NotificationChannel channel = new NotificationChannel();
        channel.setOrgId(orgId);
        channel.setType(type);
        channel.setEnabled(enabled);
        channel.setCredentialsEncrypted(enabled ? "ciphertext" : null);
        return channel;
    }
}
