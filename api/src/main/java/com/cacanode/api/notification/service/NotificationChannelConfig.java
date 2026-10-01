package com.cacanode.api.notification.service;

/**
 * Decrypted, in-memory mail settings for one delivery. Values come either from
 * an enabled {@code notification_channels} row (organization settings, stored
 * encrypted) or from the environment as the bootstrap default.
 *
 * <p>Never log this record and never serialize it into an API response: it
 * carries the relay password or provider API key.
 *
 * @param type       provider type: {@code smtp}, {@code sendgrid}, {@code brevo}
 * @param fromEmail  envelope sender address
 * @param fromName   display name of the sender
 * @param host       SMTP host (smtp only)
 * @param port       SMTP port (smtp only)
 * @param username   SMTP auth user, blank when the relay needs no auth (smtp only)
 * @param password   SMTP auth password (smtp only)
 * @param smtpAuth   whether SMTP AUTH is used
 * @param starttls   whether STARTTLS is negotiated
 * @param apiKey     SendGrid/Brevo API key (provider types only)
 */
public record NotificationChannelConfig(
        String type,
        String fromEmail,
        String fromName,
        String host,
        int port,
        String username,
        String password,
        boolean smtpAuth,
        boolean starttls,
        String apiKey) {

    public boolean isSmtp() {
        return "smtp".equalsIgnoreCase(type);
    }
}
