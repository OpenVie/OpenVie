package com.cacanode.api.notification.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "EMAIL-SERVICE")
@Service
public class EmailService {

    private final EmailProvider primaryProvider;
    private final EmailProvider fallbackProvider;
    private final String verificationLink;
    private final String login2FALink;
    private final String invitationLink;

    /**
     * The active provider comes from configuration ({@code app.email.provider},
     * env {@code MAIL_PROVIDER}) and is validated at startup by
     * {@link MailConfigurationValidator}. An optional secondary provider
     * ({@code app.email.fallback-provider}) is only used when configured;
     * without one a delivery failure propagates instead of being skipped.
     */
    @Autowired
    public EmailService(
            List<EmailProvider> providers,
            @Value("${app.email.provider:sendgrid}") String providerName,
            @Value("${app.email.fallback-provider:}") String fallbackProviderName,
            @Value("${app.email.verification-link:}") String verificationLink,
            @Value("${app.email.login-2fa-link:http://localhost:3000/verify-login}") String login2FALink,
            @Value("${app.email.invitation-link:http://localhost:3000/accept-invitation}") String invitationLink) {
        this(selectProvider(providers, providerName, "app.email.provider"),
                fallbackProviderName.isBlank()
                        ? null
                        : selectProvider(providers, fallbackProviderName, "app.email.fallback-provider"),
                verificationLink, login2FALink, invitationLink);
    }

    EmailService(EmailProvider primaryProvider, EmailProvider fallbackProvider,
                 String verificationLink, String login2FALink, String invitationLink) {
        this.primaryProvider = primaryProvider;
        this.fallbackProvider = fallbackProvider;
        this.verificationLink = verificationLink;
        this.login2FALink = login2FALink;
        this.invitationLink = invitationLink;
    }

    EmailService(EmailProvider primaryProvider, EmailProvider fallbackProvider,
                 String verificationLink, String login2FALink) {
        this(primaryProvider, fallbackProvider, verificationLink, login2FALink,
                "http://localhost:3000/accept-invitation");
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

    public void sendWelcomeEmail(String toEmail, String fullName, String companyName, String verificationToken) {
        EmailMessage message = new EmailMessage(
                toEmail,
                fullName,
                "Welcome to CacaNode - Confirm your email",
                buildWelcomeEmailHtml(fullName, companyName, verificationToken)
        );
        sendWithFallback(message);
    }

    private String buildWelcomeEmailHtml(
            String fullName,
            String companyName,
            String verificationToken) {
        String verifyUrl = verificationLink + "?token=" + verificationToken;

        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: Arial, sans-serif; background: #f9f9f9; margin: 0; padding: 0; }
                        .container { max-width: 600px; margin: 40px auto; background: #fff;
                                     border-radius: 8px; padding: 40px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); }
                        .logo { font-size: 24px; font-weight: bold; color: #4f46e5; margin-bottom: 24px; }
                        h1 { font-size: 22px; color: #111827; }
                        p { color: #6b7280; line-height: 1.6; }
                        .btn { display: inline-block; padding: 12px 28px; background: #4f46e5;
                               color: #fff!important; text-decoration: none; border-radius: 6px;
                               font-weight: bold; margin: 24px 0; }
                        .footer { margin-top: 32px; font-size: 12px; color: #9ca3af; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="logo">CacaNode</div>
                        <h1>Welcome, %s!</h1>
                        <p>
                            Thank you for registering <strong>%s</strong> on CacaNode.
                            Your AI-powered chatbot platform is ready to set up.
                        </p>
                        <p>Please confirm your email address to activate your account:</p>
                        <a href="%s" class="btn">Confirm Email</a>
                        <p>If you did not create this account, you can safely ignore this email.</p>
                        <div class="footer">
                            © 2026 CacaNode. All rights reserved.<br>
                            This link expires in 24 hours.
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(fullName, companyName, verifyUrl);
    }

    public void sendLogin2FAEmail(String toEmail, String fullName, String verificationToken) {
        EmailMessage message = new EmailMessage(
                toEmail,
                fullName,
                "Login Verification - CacaNode",
                buildLogin2FAEmailHtml(fullName, verificationToken)
        );
        sendWithFallback(message);
    }

    public void sendLogin2FACodeEmail(String toEmail, String fullName, String confirmationCode) {
        EmailMessage message = new EmailMessage(
                toEmail,
                fullName,
                "Your CacaNode confirmation code",
                buildLogin2FACodeEmailHtml(fullName, confirmationCode)
        );
        sendWithFallback(message);
    }

    public void sendInvitationEmail(String toEmail, String tenantName, String role,
                                    String token, LocalDateTime expiresAt) {
        String inviteUrl = invitationLink + "?token=" + token;
        String roleLabel = "TENANT_ADMIN".equals(role) ? "Tenant admin" : "User";
        EmailMessage message = new EmailMessage(
                toEmail,
                toEmail,
                "You're invited to join " + tenantName + " on CacaNode",
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
                <div class="logo">CacaNode</div>
                <h1>Join %s</h1>
                <p>You have been invited to join <strong>%s</strong> as a <strong>%s</strong>.</p>
                <a href="%s" class="btn">Accept invitation</a>
                <p>If you were not expecting this invitation, you can safely ignore this email.</p>
                <div class="footer">This link expires at %s (72 hours after it was sent).</div>
                </div></body></html>
                """.formatted(tenantName, tenantName, roleLabel, inviteUrl,
                        expiresAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
        );
        sendWithFallback(message);
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

    private String buildLogin2FAEmailHtml(String fullName, String verificationToken) {
        String verifyUrl = login2FALink + "?token=" + verificationToken;

        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: Arial, sans-serif; background: #f9f9f9; margin: 0; padding: 0; }
                        .container { max-width: 600px; margin: 40px auto; background: #fff;
                                     border-radius: 8px; padding: 40px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); }
                        .logo { font-size: 24px; font-weight: bold; color: #4f46e5; margin-bottom: 24px; }
                        h1 { font-size: 22px; color: #111827; }
                        p { color: #6b7280; line-height: 1.6; }
                        .btn { display: inline-block; padding: 12px 28px; background: #4f46e5;
                               color: #fff!important; text-decoration: none; border-radius: 6px;
                               font-weight: bold; margin: 24px 0; }
                        .footer { margin-top: 32px; font-size: 12px; color: #9ca3af; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="logo">CacaNode</div>
                        <h1>Hello, %s!</h1>
                        <p>
                            We received a login request for your CacaNode account.
                            Please click the button below to verify and complete your login.
                        </p>
                        <a href="%s" class="btn">Verify Login</a>
                        <p>If you did not attempt to log in, please ignore this email and ensure your account is secure.</p>
                        <div class="footer">
                            © 2026 CacaNode. All rights reserved.<br>
                            This link expires in 15 minutes.
                        </div>
                    </div>
                </body>
                </html>
                """
                .formatted(fullName, verifyUrl);
    }

    private String buildLogin2FACodeEmailHtml(String fullName, String confirmationCode) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><style>
                body { font-family: Arial, sans-serif; background:#f9f9f9; margin:0; padding:0; }
                .container { max-width:600px; margin:40px auto; background:#fff; border-radius:8px;
                  padding:40px; box-shadow:0 2px 8px rgba(0,0,0,.08); }
                .logo { font-size:24px; font-weight:bold; color:#4f46e5; margin-bottom:24px; }
                h1 { font-size:22px; color:#111827; } p { color:#6b7280; line-height:1.6; }
                .code { display:inline-block; padding:16px 24px; margin:20px 0; border-radius:8px;
                  background:#eef2ff; color:#312e81; font-size:32px; font-weight:bold;
                  letter-spacing:8px; }
                .footer { margin-top:32px; font-size:12px; color:#9ca3af; }
                </style></head><body><div class="container">
                <div class="logo">CacaNode</div>
                <h1>Hello, %s!</h1>
                <p>Enter this confirmation code in the CacaNode mobile app to complete sign-in:</p>
                <div class="code">%s</div>
                <p>If you did not attempt to sign in, you can safely ignore this email.</p>
                <div class="footer">This code expires in 10 minutes and can be used once.</div>
                </div></body></html>
                """.formatted(fullName, confirmationCode);
    }
}
