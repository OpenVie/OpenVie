package com.cacanode.api.notification.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Renders and sends the one email this product delivers: a workspace
 * invitation.
 *
 * <p>Login verification and welcome mail are gone — login is password-only and
 * email is optional, so an installation with no channel never blocks a human.
 * The transport and credentials are resolved per organization by
 * {@link NotificationChannelResolver}: a stored enabled channel wins, the
 * process environment is the bootstrap default.
 */
@Slf4j(topic = "EMAIL-SERVICE")
@Service
public class EmailService {

    private final NotificationChannelResolver resolver;
    private final String invitationLink;

    public EmailService(
            NotificationChannelResolver resolver,
            @Value("${app.email.invitation-link:http://localhost:3000/accept-invitation}")
            String invitationLink) {
        this.resolver = resolver;
        this.invitationLink = invitationLink;
    }

    public void sendInvitationEmail(
            UUID orgId,
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

        NotificationChannelResolver.ResolvedChannel resolved = resolver.resolve(orgId)
                .orElseThrow(() -> new EmailDeliveryException(
                        "Organization %s has no enabled notification channel; the invitation is"
                        + " held until one is configured".formatted(orgId)));
        if (!resolved.provider().available(resolved.config())) {
            throw new EmailDeliveryException("%s is selected for organization %s but is not"
                    + " usable with the current settings".formatted(
                            resolved.provider().providerName(), orgId));
        }
        resolved.provider().send(message, resolved.config());
        log.info("Email sent to {} via {}", message.toEmail(), resolved.provider().providerName());
    }

    private static String roleLabel(String role) {
        return switch (role == null ? "" : role.trim().toUpperCase(Locale.ROOT)) {
            case "WORKSPACE_ADMIN" -> "workspace admin";
            default -> "member";
        };
    }
}
