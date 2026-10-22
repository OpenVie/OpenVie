// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.notification.service;

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

import com.openvie.api.notification.model.NotificationChannel;
import com.openvie.api.notification.repository.NotificationChannelRepository;
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
