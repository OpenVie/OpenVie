package com.cacanode.api.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.cacanode.api.common.exception.custom.BadRequestException;
import com.cacanode.api.common.exception.custom.ConflictException;
import com.cacanode.api.notification.model.NotificationChannel;
import com.cacanode.api.notification.repository.NotificationChannelRepository;
import com.cacanode.api.notification.service.NotificationChannelService.ChannelRequest;
import com.cacanode.api.notification.service.NotificationChannelService.ChannelView;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Channel administration: credentials are encrypted at rest and never echoed,
 * enabling requires usable settings, and unknown types fail with the
 * supported list.
 */
class NotificationChannelServiceTest {

    private NotificationChannelRepository channelRepository;
    private CredentialCipher cipher;
    private NotificationChannelResolver resolver;
    private NotificationChannelService service;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        channelRepository = mock(NotificationChannelRepository.class);
        cipher = mock(CredentialCipher.class);
        resolver = mock(NotificationChannelResolver.class);
        service = new NotificationChannelService(
                channelRepository, cipher, resolver, new ObjectMapper());
        orgId = UUID.randomUUID();
        when(cipher.available()).thenReturn(true);
        when(cipher.encrypt(anyString())).thenAnswer(call -> "enc(" + call.getArgument(0) + ")");
        when(channelRepository.save(any(NotificationChannel.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void upsertStoresEncryptedCredentialsAndNeverEchoesThem() {
        when(channelRepository.findByOrgIdAndType(orgId, "smtp")).thenReturn(Optional.empty());
        givenUsableProvider();

        ChannelView view = service.upsert(orgId, new ChannelRequest(
                "smtp", true, null, null, "relay.local", null, null, "s3cret",
                null, null, null));

        ArgumentCaptor<String> plaintext = ArgumentCaptor.forClass(String.class);
        verify(cipher).encrypt(plaintext.capture());
        assertTrue(plaintext.getValue().contains("s3cret"),
                "the cipher receives the merged credentials: " + plaintext.getValue());
        ArgumentCaptor<NotificationChannel> saved =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(channelRepository).save(saved.capture());
        assertEquals("enc(" + plaintext.getValue() + ")",
                saved.getValue().getCredentialsEncrypted(),
                "only ciphertext reaches the row");
        // The view reports presence only.
        assertTrue(view.credentialsStored());
        assertEquals("smtp", view.type());
        assertTrue(view.enabled());
    }

    @Test
    void upsertMergesNewFieldsOverStoredCredentials() {
        NotificationChannel existing = new NotificationChannel();
        existing.setId(UUID.randomUUID());
        existing.setOrgId(orgId);
        existing.setType("smtp");
        existing.setCredentialsEncrypted("enc-existing");
        when(channelRepository.findByOrgIdAndType(orgId, "smtp"))
                .thenReturn(Optional.of(existing));
        when(cipher.decrypt("enc-existing")).thenReturn(
                "{\"host\":\"old.relay\",\"port\":25,\"password\":\"keep-me\"}");
        givenUsableProvider();

        service.upsert(orgId, new ChannelRequest(
                "smtp", true, null, null, "new.relay", null, null, null, null, null, null));

        ArgumentCaptor<String> encrypted = ArgumentCaptor.forClass(String.class);
        verify(cipher).encrypt(encrypted.capture());
        String payload = encrypted.getValue().substring(4, encrypted.getValue().length() - 1);
        assertTrue(payload.contains("new.relay"), "host updated: " + payload);
        assertTrue(payload.contains("keep-me"), "untouched password preserved: " + payload);
        assertTrue(payload.contains("25"), "untouched port preserved: " + payload);
    }

    @Test
    void storedChannelsRequireTheEncryptionKey() {
        when(cipher.available()).thenReturn(false);

        ConflictException exception = assertThrows(ConflictException.class,
                () -> service.upsert(orgId, new ChannelRequest(
                        "smtp", true, null, null, null, null, null, null, null, null, null)));
        assertTrue(exception.getMessage().contains("NOTIFICATION_ENC_KEY"),
                "must name the missing variable: " + exception.getMessage());
    }

    @Test
    void enablingWithoutUsableCredentialsIsRejected() {
        when(channelRepository.findByOrgIdAndType(orgId, "smtp")).thenReturn(Optional.empty());
        EmailProvider unusable = mock(EmailProvider.class);
        when(unusable.available(any())).thenReturn(false);
        when(resolver.providerFor(any())).thenReturn(Optional.of(unusable));
        when(resolver.configOf(any())).thenReturn(
                new NotificationChannelConfig("smtp", "", "", "", 587, "", "",
                        false, false, ""));

        assertThrows(BadRequestException.class,
                () -> service.upsert(orgId, new ChannelRequest(
                        "smtp", true, null, null, null, null, null, null, null, null, null)));
    }

    @Test
    void unknownTypeListsTheSupportedOnes() {
        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> service.upsert(orgId, new ChannelRequest(
                        "carrier-pigeon", true, null, null, null, null, null, null,
                        null, null, null)));
        assertTrue(exception.getMessage().contains("smtp"),
                "must list supported types: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("sendgrid"));
        assertTrue(exception.getMessage().contains("brevo"));
    }

    @Test
    void enablingAChannelWithoutUsableCredentialsIsRejected() {
        NotificationChannel channel = new NotificationChannel();
        channel.setId(UUID.randomUUID());
        channel.setOrgId(orgId);
        channel.setType("smtp");
        channel.setEnabled(false);
        when(channelRepository.findById(channel.getId())).thenReturn(Optional.of(channel));
        when(resolver.providerFor(any())).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class,
                () -> service.setEnabled(orgId, channel.getId(), true));
        verify(channelRepository, never()).save(any());
    }

    @Test
    void listingReportsPresenceNotSecrets() {
        NotificationChannel channel = new NotificationChannel();
        channel.setId(UUID.randomUUID());
        channel.setOrgId(orgId);
        channel.setType("sendgrid");
        channel.setEnabled(true);
        channel.setCredentialsEncrypted("enc(...)");
        when(channelRepository.findByOrgIdOrderByCreatedAtAsc(orgId))
                .thenReturn(List.of(channel));

        List<ChannelView> views = service.list(orgId);

        assertEquals(1, views.size());
        assertTrue(views.get(0).credentialsStored());
        assertFalse(views.toString().contains("enc(...)"),
                "views must never carry ciphertext: " + views);
    }

    private void givenUsableProvider() {
        EmailProvider provider = mock(EmailProvider.class);
        when(provider.available(any())).thenReturn(true);
        when(resolver.providerFor(any())).thenReturn(Optional.of(provider));
        when(resolver.configOf(any())).thenReturn(
                new NotificationChannelConfig("smtp", "a@b.c", "OpenVie",
                        "relay.local", 587, "", "", true, true, ""));
    }
}
