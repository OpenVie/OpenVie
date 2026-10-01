package com.cacanode.api.notification.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import lombok.extern.slf4j.Slf4j;

/**
 * Renders and sends the one email this product delivers: a workspace
 * invitation.
 *
 * <p>Login verification and welcome mail are gone — login is password-only and
 * email is optional, so an installation with no channel never blocks a human.
 * Callers reach delivery through invitation creation, which refuses to mint a
 * token when no channel is available.
 */
@Slf4j(topic = "EMAIL-SERVICE")
@Service
public class EmailService {

    private final EmailProvider primaryProvider;
    private final EmailProvider fallbackProvider;
    private final String invitationLink;

    /**
     * The active provider comes from configuration ({@code app.email.provider},
     * env {@code MAIL_PROVIDER}). An optional secondary provider
     * ({@code app.email.fallback-provider}) is only used when configured;
     * without one a delivery failure propagates instead of being skipped.
     */
    @Autowired
    public EmailService(
            List<EmailProvider> providers,
            @Value("${app.email.provider:sendgrid}") String providerName,
            @Value("${app.email.fallback-provider:}") String fallbackProviderName,
            @Value("${app.email.invitation-link:http://localhost:3000/accept-invitation}") String invitationLink) {
        this(selectProvider(providers, providerName, "app.email.provider"),
                fallbackProviderName.isBlank()
                        ? null
                        : selectProvider(providers, fallbackProviderName, "app.email.fallback-provider"),
                invitationLink);
    }

    EmailService(EmailProvider primaryProvider, EmailProvider fallbackProvider, String invitationLink) {
        this.primaryProvider = primaryProvider;
        this.fallbackProvider = fallbackProvider;
        this.invitationLink = invitationLink;
    }

    private static EmailProvider selectProvider(
            List<EmailProvider> providers, String providerName, String property) {
        String wanted = providerName.trim().toLowerCase(Locale.ROOT);
        return providers.stream()
                .filter(provider -> provider.providerName().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        property + "='" + providerName + "' is not a known email provider; configured providers are "
                                + providers.stream().map(EmailProvider::providerName).sorted().toList()));
    }

    public void sendInvitationEmail(
            String toEmail,
            String organizationName,
            String workspaceName,
            String role,
            String token,
            LocalDateTime expiresAt) {
        String inviteUrl = invitationLink + "?token=" + token;
        String roleLabel = roleLabel(role);
        EmailMessage message = new EmailMessage(
                toEmail,
                toEmail,
                "You're invited to join %s on OpenVie".formatted(workspaceName),
                """
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8"><style>
                body { font-family: Arial, sans-serif; background:#f9f9f9; margin:0; padding:0; }
                .container { max-width:600px; margin:40px auto; background:#fff; border-radius:8px;
                  padding:40px; box-shadow:0 2px 8px rgba(0,0,0,.08); }
                .logo { font-size:24px; font-weight:bold; color:#4f46e5; margin-bottom:24px; }
                h1 { font-size:22px; color:#111827; } p { color:#6b7280; line-height:1.6; }
                .btn { display:inline-block; padding:12px 28px; background:#4f46e5; color:#fff!important;
                  text-decoration:none; border-radius:6px; font-weight:bold; margin:24px 0; }
                .footer { margin-top:32px; font-size:12px; color:#9ca3af; }
                </style></head><body><div class="container">
                <div class="logo">OpenVie</div>
                <h1>Join %1$s</h1>
                <p>You have been invited to <strong>%2$s</strong> (%3$s) as a <strong>%4$s</strong>.</p>
                <a href="%5$s" class="btn">Accept invitation</a>
                <p>If you were not expecting this invitation, you can safely ignore this email.</p>
                <div class="footer">This link expires at %6$s (72 hours after it was sent).</div>
                </div></body></html>
                """.formatted(workspaceName, organizationName, workspaceName, roleLabel, inviteUrl,
                        expiresAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
        );
        sendWithFallback(message);
    }

    private static String roleLabel(String role) {
        return switch (role == null ? "" : role.trim().toUpperCase(Locale.ROOT)) {
            case "WORKSPACE_ADMIN" -> "workspace admin";
            default -> "member";
        };
    }

    private void sendWithFallback(EmailMessage message) {
        try {
            primaryProvider.send(message);
            log.info("Email sent to {} via {}", message.toEmail(), primaryProvider.providerName());
            return;
        } catch (EmailDeliveryException primaryFailure) {
            if (fallbackProvider == null) {
                log.error(
                        "{} failed to send email to {} and no fallback provider is configured. Reason: {}",
                        primaryProvider.providerName(),
                        message.toEmail(),
                        primaryFailure.getMessage()
                );
                throw primaryFailure;
            }
            log.warn(
                    "{} failed to send email to {}. Trying {}. Reason: {}",
                    primaryProvider.providerName(),
                    message.toEmail(),
                    fallbackProvider.providerName(),
                    primaryFailure.getMessage()
            );
            try {
                fallbackProvider.send(message);
                log.info("Email sent to {} via {}", message.toEmail(), fallbackProvider.providerName());
            } catch (EmailDeliveryException fallbackFailure) {
                EmailDeliveryException deliveryFailure = new EmailDeliveryException(
                        "Email delivery failed with %s and %s".formatted(
                                primaryProvider.providerName(),
                                fallbackProvider.providerName()
                        ),
                        fallbackFailure
                );
                deliveryFailure.addSuppressed(primaryFailure);
                throw deliveryFailure;
            }
        }
    }
}
