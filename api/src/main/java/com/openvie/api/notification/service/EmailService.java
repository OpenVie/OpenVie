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

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Renders and sends the transactional emails this product delivers: a
 * workspace invitation, a membership-added notice, and an account-created
 * notice.
 *
 * <p>The layout mirrors the app's dark theme — near-black card, lime
 * accent — with the brand logo on top (the PNG, because the common webmail
 * clients do not render SVG). The transport and credentials are resolved per
 * organization by {@link NotificationChannelResolver}: a stored enabled
 * channel wins, the process environment is the bootstrap default.
 */
@Slf4j(topic = "EMAIL-SERVICE")
@Service
public class EmailService {

    private final NotificationChannelResolver resolver;
    private final String invitationLink;
    private final String loginLink;
    private final String logoUrl;

    public EmailService(
            NotificationChannelResolver resolver,
            @Value("${app.email.invitation-link:http://localhost:3000/accept-invitation}")
            String invitationLink,
            @Value("${app.email.login-link:http://localhost:3000/login}")
            String loginLink,
            @Value("${app.email.logo-url:}")
            String logoUrl) {
        this.resolver = resolver;
        this.invitationLink = invitationLink;
        this.loginLink = loginLink;
        this.logoUrl = resolveLogoUrl(logoUrl, loginLink);
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
        String roleLabelText = roleLabel(role);
        EmailMessage message = new EmailMessage(
                toEmail,
                toEmail,
                "You're invited to join %s on OpenVie".formatted(workspaceName),
                renderShell("""
                <h1>Join %1$s</h1>
                <p>You have been invited to <strong>%2$s</strong> (%3$s) as a <strong>%4$s</strong>.</p>
                <a href="%5$s" class="btn">Accept invitation</a>
                <p>If you were not expecting this invitation, you can safely
                ignore this email.</p>
                <div class="footer">This link expires at %6$s (72 hours after
                it was sent).</div>
                """.formatted(html(workspaceName), html(organizationName), html(workspaceName),
                        roleLabelText, inviteUrl,
                        expiresAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))))
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

    /**
     * Best-effort notice that an existing account was added to a workspace by
     * an administrator. Returns false when no channel is configured or the
     * transport failed — membership itself never depends on the mail.
     */
    public boolean sendWorkspaceAddedEmail(UUID orgId, String toEmail, String fullName,
            String organizationName, String workspaceName, String role) {
        String roleText = roleLabel(role);
        String displayName = fullName == null || fullName.isBlank() ? toEmail : fullName;
        EmailMessage message = new EmailMessage(
                toEmail,
                toEmail,
                "You've been added to %s on OpenVie".formatted(workspaceName),
                renderShell("""
                <h1>You've been added to %1$s</h1>
                <p>Hi %2$s,</p>
                <p>An administrator added your account <strong>%3$s</strong> to the
                <strong>%1$s</strong> workspace (%4$s) on OpenVie as a <strong>%5$s</strong>.
                No action is needed — the workspace already appears in OpenVie after you
                sign in.</p>
                <a href="%6$s" class="btn">Sign in to OpenVie</a>
                <div class="footer">You received this email because an organization
                administrator added your account to this workspace.</div>
                """.formatted(html(workspaceName), html(displayName), html(toEmail),
                        html(organizationName), roleText, loginLink))
        );
        return trySend(orgId, message, "workspace-add notice");
    }

    /**
     * Best-effort notice that an administrator created an account for the
     * recipient. The email carries no password — the administrator shares the
     * temporary password directly; the first sign-in forces a password change.
     */
    public boolean sendAccountCreatedEmail(UUID orgId, String toEmail, String fullName) {
        String displayName = fullName == null || fullName.isBlank() ? toEmail : fullName;
        EmailMessage message = new EmailMessage(
                toEmail,
                toEmail,
                "Your OpenVie account has been created",
                renderShell("""
                <h1>Welcome, %1$s</h1>
                <p>An OpenVie account has been created for you. The administrator who
                created it shares the temporary password with you directly.</p>
                <p>When you first sign in, OpenVie asks you to set your own password
                before you can continue.</p>
                <a href="%2$s" class="btn">Log in to OpenVie</a>
                <p>If you were not expecting an account, you can safely ignore this email.</p>
                <div class="footer">You received this email because an organization
                administrator created an account for this address.</div>
                """.formatted(html(displayName), loginLink))
        );
        return trySend(orgId, message, "account-created notice");
    }

    /**
     * Best-effort notice to the submitter that an approval process settled.
     * Returns false when no channel is configured or the transport failed —
     * the decision itself is already committed and never depends on the mail.
     */
    public boolean sendWorkflowDecisionEmail(UUID orgId, String toEmail, String fullName,
            String documentFileName, String decision, String reason, int approvalLevel,
            String publishTarget) {
        boolean approved = "APPROVE".equalsIgnoreCase(decision);
        String verdict = approved ? "approved" : "rejected";
        String displayName = fullName == null || fullName.isBlank() ? toEmail : fullName;
        String fileName = documentFileName == null || documentFileName.isBlank()
                ? "your submission"
                : documentFileName;
        String reasonBlock = approved || reason == null || reason.isBlank()
                ? ""
                : "<p><strong>Reason:</strong> %s</p>".formatted(html(reason));
        EmailMessage message = new EmailMessage(
                toEmail,
                toEmail,
                "Your document was %s on OpenVie".formatted(verdict),
                renderShell("""
                <h1>Document %1$s</h1>
                <p>Hi %2$s,</p>
                <p><strong>%3$s</strong> was %4$s at approval level %5$s (publish target: %6$s).</p>
                %7$s
                <a href="%8$s" class="btn">Open OpenVie</a>
                <div class="footer">You received this email because you submitted this
                document for approval.</div>
                """.formatted(approved ? "approved" : "rejected", html(displayName), html(fileName),
                        verdict, approvalLevel, html(publishTarget), reasonBlock, loginLink))
        );
        return trySend(orgId, message, "workflow-decision notice");
    }

    /**
     * Shared dark-brand shell: page background, card, logo, type colors and
     * button mirror the app's dark theme tokens (#07090c background, #c8f53c
     * primary with #07090c text on it).
     */
    private String renderShell(String contentHtml) {
        return """
        <!DOCTYPE html>
        <html lang="en"><head><meta charset="UTF-8">
        <meta name="viewport" content="width=device-width">
        <style>
          body { font-family: Arial, sans-serif; background:#07090c; margin:0; padding:0; }
          .wrapper { padding:32px 16px; }
          .container { max-width:560px; margin:0 auto; background:#10141a;
            border:1px solid rgba(255,255,255,0.12); border-radius:14px; padding:40px; }
          .logo { display:block; width:44px; height:44px; border-radius:10px; margin-bottom:22px; }
          h1 { font-size:22px; line-height:1.3; color:#f2f5f0; margin:0 0 14px; }
          p { color:#a9b3ab; line-height:1.65; margin:0 0 14px; }
          strong { color:#e6ebe4; }
          .btn { display:inline-block; padding:12px 26px; background:#c8f53c; color:#07090c !important;
            text-decoration:none; border-radius:8px; font-weight:bold; font-size:14px; margin:10px 0 16px; }
          .footer { margin-top:30px; padding-top:16px; border-top:1px solid rgba(255,255,255,0.08);
            font-size:12px; color:rgba(255,255,255,0.45); line-height:1.5; }
        </style></head><body>
        <div class="wrapper"><div class="container">
        <img class="logo" src="%s" width="44" height="44" alt="OpenVie" />
        %s
        </div></div></body></html>
        """.formatted(logoUrl, contentHtml);
    }

    /** Send unless no channel is configured; failures never propagate. */
    private boolean trySend(UUID orgId, EmailMessage message, String purpose) {
        return resolver.resolve(orgId)
                .filter(resolved -> resolved.provider().available(resolved.config()))
                .map(resolved -> {
                    try {
                        resolved.provider().send(message, resolved.config());
                        log.info("Email ({}) sent to {} via {}", purpose, message.toEmail(),
                                resolved.provider().providerName());
                        return true;
                    } catch (EmailDeliveryException exception) {
                        log.warn("Email ({}) to {} failed: {}", purpose, message.toEmail(),
                                exception.getMessage());
                        return false;
                    }
                })
                .orElseGet(() -> {
                    log.info("Email ({}) to {} skipped: no usable notification channel for {}",
                            purpose, message.toEmail(), orgId);
                    return false;
                });
    }

    /** Escape user-supplied text for the HTML templates. */
    private static String html(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * Absolute PNG logo URL rendered in every email. When EMAIL_LOGO_URL is
     * unset it is derived from the login link's origin, so a deployment that
     * configured LOGIN_LINK gets a working logo without extra configuration.
     */
    static String resolveLogoUrl(String configured, String loginLink) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            URI base = URI.create(loginLink);
            boolean realWebOrigin = base.isAbsolute() && ("http".equalsIgnoreCase(base.getScheme())
                    || "https".equalsIgnoreCase(base.getScheme()));
            if (realWebOrigin) {
                return base.resolve("/logo.png").toString();
            }
        } catch (IllegalArgumentException ignored) {
            // Not a usable URL; fall through to the local default.
        }
        return "http://localhost:3000/logo.png";
    }
}
