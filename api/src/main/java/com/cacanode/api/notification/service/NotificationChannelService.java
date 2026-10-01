package com.cacanode.api.notification.service;

import com.cacanode.api.common.exception.custom.BadRequestException;
import com.cacanode.api.common.exception.custom.ConflictException;
import com.cacanode.api.common.exception.custom.ResourceNotFoundException;
import com.cacanode.api.notification.model.NotificationChannel;
import com.cacanode.api.notification.repository.NotificationChannelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Organization-level notification channel administration.
 *
 * <p>Channels are the plugin instances an operator switches on: the type
 * selects an in-repo {@link EmailProvider} bean, the credentials are stored
 * encrypted (AES-GCM via {@link CredentialCipher}) and are never returned
 * after creation — reads report presence, not values.
 *
 * <p>Exactly one channel per (org, type) may exist; enabling one is what the
 * outbox gate and the invitation flow consult.
 */
@Service
@Slf4j(topic = "CHANNEL-SERVICE")
@RequiredArgsConstructor
public class NotificationChannelService {

    private static final Set<String> KNOWN_TYPES = Set.of("smtp", "sendgrid", "brevo");

    private final NotificationChannelRepository channelRepository;
    private final CredentialCipher cipher;
    private final NotificationChannelResolver resolver;
    private final ObjectMapper objectMapper;

    public record ChannelView(
            UUID id,
            String type,
            boolean enabled,
            boolean credentialsStored,
            String lastDeliveryError,
            LocalDateTime lastDeliveryAt) {
    }

    /** Upsert request. Null credential fields keep the stored value on update. */
    public record ChannelRequest(
            String type,
            Boolean enabled,
            String fromEmail,
            String fromName,
            String host,
            Integer port,
            String username,
            String password,
            Boolean auth,
            Boolean starttls,
            String apiKey) {
    }

    @Transactional(readOnly = true)
    public List<ChannelView> list(UUID orgId) {
        return channelRepository.findByOrgIdOrderByCreatedAtAsc(orgId).stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public ChannelView upsert(UUID orgId, ChannelRequest request) {
        String type = normalizeType(request.type());
        if (!cipher.available()) {
            throw new ConflictException(
                    "Stored notification channels require NOTIFICATION_ENC_KEY; generate one with"
                            + " 'openssl rand -base64 32'. Environment-configured mail keeps"
                            + " working without it.");
        }
        NotificationChannel channel = channelRepository.findByOrgIdAndType(orgId, type)
                .orElseGet(() -> {
                    NotificationChannel created = new NotificationChannel();
                    created.setOrgId(orgId);
                    created.setType(type);
                    return created;
                });

        if (request.enabled() != null) {
            channel.setEnabled(request.enabled());
        }
        String mergedCredentials = mergeCredentials(channel, request);
        if (mergedCredentials != null) {
            channel.setCredentialsEncrypted(cipher.encrypt(mergedCredentials));
        }
        if (channel.isEnabled()) {
            requireUsable(channel);
        }
        NotificationChannel saved = channelRepository.save(channel);
        log.info("Channel upserted org={} type={} enabled={}", orgId, type, saved.isEnabled());
        return view(saved);
    }

    @Transactional
    public ChannelView setEnabled(UUID orgId, UUID channelId, boolean enabled) {
        NotificationChannel channel = require(orgId, channelId);
        if (enabled) {
            requireUsable(channel);
        }
        channel.setEnabled(enabled);
        return view(channelRepository.save(channel));
    }

    /**
     * Validate the in-memory row: resolve() would read the database, which is
     * stale inside this transaction for an upsert that has not been flushed.
     */
    private void requireUsable(NotificationChannel channel) {
        EmailProvider provider = resolver.providerFor(channel.getType())
                .orElseThrow(() -> new BadRequestException(
                        "Channel type '" + channel.getType() + "' has no registered transport"));
        if (!provider.available(resolver.configOf(channel))) {
            throw new BadRequestException(
                    "Channel '" + channel.getType() + "' cannot be enabled without complete"
                    + " credentials for " + provider.providerName());
        }
    }

    @Transactional
    public void delete(UUID orgId, UUID channelId) {
        channelRepository.delete(require(orgId, channelId));
    }

    /**
     * Sends a one-off message through the organization's currently active
     * channel so an operator can verify credentials before inviting anyone.
     */
    @Transactional(readOnly = true)
    public void testDelivery(UUID orgId, String toEmail) {
        NotificationChannelResolver.ResolvedChannel resolved = resolver.resolve(orgId)
                .orElseThrow(() -> new ConflictException(
                        "No notification channel is active for this organization"));
        if (!resolved.provider().available(resolved.config())) {
            throw new ConflictException(resolved.provider().providerName()
                    + " is selected but not usable with the current settings");
        }
        EmailMessage message = new EmailMessage(
                toEmail, toEmail, "OpenVie test message",
                "<p>This is a test message from OpenVie. Your notification channel is working.</p>");
        resolved.provider().send(message, resolved.config());
    }

    private NotificationChannel require(UUID orgId, UUID channelId) {
        NotificationChannel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification channel not found"));
        if (!channel.getOrgId().equals(orgId)) {
            throw new ResourceNotFoundException("Notification channel not found");
        }
        return channel;
    }

    private String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (!KNOWN_TYPES.contains(normalized)) {
            throw new BadRequestException("Unknown channel type '" + type + "'; supported types: "
                    + KNOWN_TYPES.stream().sorted().toList());
        }
        return normalized;
    }

    /** Merge request fields over stored credentials; nulls keep old values. */
    private String mergeCredentials(NotificationChannel channel, ChannelRequest request) {
        ObjectNode merged = objectMapper.createObjectNode();
        String existing = channel.getCredentialsEncrypted();
        if (existing != null && !existing.isBlank() && cipher.available()) {
            try {
                merged.setAll((ObjectNode) objectMapper.readTree(cipher.decrypt(existing)));
            } catch (Exception exception) {
                log.warn("Stored credentials for channel {} could not be merged; overwriting",
                        channel.getId());
            }
        }
        if (request.fromEmail() != null) merged.put("fromEmail", request.fromEmail());
        if (request.fromName() != null) merged.put("fromName", request.fromName());
        if (request.host() != null) merged.put("host", request.host());
        if (request.port() != null) merged.put("port", request.port());
        if (request.username() != null) merged.put("username", request.username());
        if (request.password() != null) merged.put("password", request.password());
        if (request.auth() != null) merged.put("auth", request.auth());
        if (request.starttls() != null) merged.put("starttls", request.starttls());
        if (request.apiKey() != null) merged.put("apiKey", request.apiKey());
        return merged.isEmpty() ? null : merged.toString();
    }

    private ChannelView view(NotificationChannel channel) {
        return new ChannelView(
                channel.getId(),
                channel.getType(),
                channel.isEnabled(),
                channel.getCredentialsEncrypted() != null && !channel.getCredentialsEncrypted().isBlank(),
                channel.getLastDeliveryError(),
                channel.getLastDeliveryAt());
    }
}
