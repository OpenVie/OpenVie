package com.openvie.api.notification.service;

/**
 * Decrypted, in-memory mail settings for one delivery. Values come either from
 * an enabled {@code notification_channels} row (organization settings, stored
 * encrypted) or from the environment as the bootstrap default.
 *
 * <p>Never log this record and never serialize it into an API response: it
 * carries the SMTP relay password.
 *
 * @param type       provider type: {@code smtp}
 * @param fromEmail  envelope sender address
 * @param fromName   display name of the sender
 * @param host       SMTP host
 * @param port       SMTP port
 * @param username   SMTP auth user, blank when the relay needs no auth
 * @param password   SMTP auth password
 * @param smtpAuth   whether SMTP AUTH is used
 * @param starttls   whether STARTTLS is negotiated
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
        boolean starttls) {

    public boolean isSmtp() {
        return "smtp".equalsIgnoreCase(type);
    }
}
