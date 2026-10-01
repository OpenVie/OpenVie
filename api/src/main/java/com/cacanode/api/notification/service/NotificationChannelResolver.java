package com.cacanode.api.notification.service;

import com.cacanode.api.notification.model.NotificationChannel;
import com.cacanode.api.notification.repository.NotificationChannelRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the mail settings for one organization.
 *
 * <p>Precedence: the organization's enabled {@code notification_channels} row
 * (credentials decrypted at read time) wins; otherwise the process environment
 * acts as the bootstrap default so an operator can wire mail with env vars
 * alone. With neither, {@link #resolve} returns empty and the installation
 * runs without email — login, ingestion, and answers never depend on it.
 */
@Service
@Slf4j(topic = "CHANNEL-RESOLVER")
@RequiredArgsConstructor
public class NotificationChannelResolver {

    private final NotificationChannelRepository channelRepository;
    private final CredentialCipher cipher;
    private final ObjectMapper objectMapper;
    private final List<EmailProvider> providers;

    @Value("${app.email.provider:none}")
    private String environmentProvider;

    @Value("${app.email.from-email:}")
    private String environmentFromEmail;

    @Value("${app.email.from-name:OpenVie}")
    private String environmentFromName;

    @Value("${spring.mail.host:}")
    private String smtpHost;

    @Value("${spring.mail.port:587}")
    private int smtpPort;

    @Value("${spring.mail.username:}")
    private String smtpUsername;

    @Value("${spring.mail.password:}")
    private String smtpPassword;

    @Value("${spring.mail.properties.mail.smtp.auth:false}")
    private boolean smtpAuth;

    @Value("${spring.mail.properties.mail.smtp.starttls.enable:false}")
    private boolean smtpStarttls;

    @Value("${spring.sendgrid.api-key:}")
    private String sendgridApiKey;

    @Value("${spring.brevo.api-key:}")
    private String brevoApiKey;

    /** The organization's active mail settings, if any. */
    @Transactional(readOnly = true)
    public Optional<ResolvedChannel> resolve(UUID orgId) {
        Optional<NotificationChannel> stored = channelRepository
                .findByOrgIdOrderByCreatedAtAsc(orgId).stream()
                .filter(NotificationChannel::isEnabled)
                .findFirst();
        if (stored.isPresent()) {
            NotificationChannel channel = stored.get();
            EmailProvider provider = providerFor(channel.getType()).orElse(null);
            if (provider == null) {
                throw new EmailDeliveryException(
                        "Stored notification channel type '" + channel.getType()
                        + "' has no registered transport; available types: "
                        + providers.stream().map(EmailProvider::type).toList());
            }
            return Optional.of(new ResolvedChannel(provider, configOf(channel)));
        }
        return environmentDefault();
    }

    /** Whether a provider is reachable from the process environment alone. */
    public Optional<ResolvedChannel> environmentDefault() {
        if (environmentProvider == null || environmentProvider.isBlank()
                || "none".equalsIgnoreCase(environmentProvider.trim())) {
            return Optional.empty();
        }
        String type = environmentProvider.trim().toLowerCase(Locale.ROOT);
        Optional<EmailProvider> provider = providerFor(type);
        if (provider.isEmpty() || environmentFromEmail == null || environmentFromEmail.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedChannel(provider.get(), environmentConfig(type)));
    }

    Optional<EmailProvider> providerFor(String type) {
        return providers.stream()
                .filter(candidate -> candidate.type().equalsIgnoreCase(type))
                .findFirst();
    }

    NotificationChannelConfig configOf(NotificationChannel channel) {
        JsonNode credentials = readCredentials(channel);
        return new NotificationChannelConfig(
                channel.getType().toLowerCase(Locale.ROOT),
                text(credentials, "fromEmail", environmentFromEmail),
                text(credentials, "fromName", environmentFromName),
                text(credentials, "host", smtpHost),
                credentials.path("port").asInt(smtpPort),
                text(credentials, "username", smtpUsername),
                text(credentials, "password", smtpPassword),
                credentials.path("auth").asBoolean(smtpAuth),
                credentials.path("starttls").asBoolean(smtpStarttls),
                text(credentials, "apiKey", apiKeyFor(channel.getType())));
    }

    private NotificationChannelConfig environmentConfig(String type) {
        return new NotificationChannelConfig(
                type, environmentFromEmail, environmentFromName,
                smtpHost, smtpPort, smtpUsername, smtpPassword,
                smtpAuth, smtpStarttls, apiKeyFor(type));
    }

    private String apiKeyFor(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "sendgrid" -> sendgridApiKey;
            case "brevo" -> brevoApiKey;
            default -> "";
        };
    }

    private JsonNode readCredentials(NotificationChannel channel) {
        String encrypted = channel.getCredentialsEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            return objectMapper.createObjectNode();
        }
        if (!cipher.available()) {
            // A stored channel exists but cannot be decrypted. Fail loudly
            // rather than delivering with wrong or missing secrets.
            log.error("Channel {} for org {} is stored but NOTIFICATION_ENC_KEY is unset;"
                    + " email delivery is disabled", channel.getType(), channel.getOrgId());
            throw new EmailDeliveryException(
                    "Stored notification channels require NOTIFICATION_ENC_KEY to be configured");
        }
        try {
            return objectMapper.readTree(cipher.decrypt(encrypted));
        } catch (JsonProcessingException exception) {
            throw new EmailDeliveryException(
                    "Stored notification channel credentials could not be read", exception);
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    /** A resolved provider plus the settings to hand it. */
    public record ResolvedChannel(EmailProvider provider, NotificationChannelConfig config) {
    }
}
