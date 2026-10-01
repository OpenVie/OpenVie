package com.cacanode.api.notification.service;

import java.nio.charset.StandardCharsets;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * SMTP transport for self-hosted installs (a local mail catcher in dev, the
 * operator's relay in production).
 *
 * <p>The sender is built per delivery from {@link NotificationChannelConfig},
 * because the relay may come from an organization's stored channel rather than
 * from process environment. No SMTP bean is required at startup.
 */
@Component
public class SmtpEmailProvider implements EmailProvider {

    @Override
    public String type() {
        return "smtp";
    }

    @Override
    public String providerName() {
        return "SMTP";
    }

    @Override
    public boolean available(NotificationChannelConfig config) {
        return config != null && config.host() != null && !config.host().isBlank();
    }

    @Override
    public void send(EmailMessage message, NotificationChannelConfig config) {
        if (!available(config)) {
            throw new EmailDeliveryException(
                    "SMTP is selected but no mail host is configured; set MAIL_HOST to your"
                    + " SMTP relay or a local mail catcher, or configure a channel in settings");
        }

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.host());
        sender.setPort(config.port());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        if (config.username() != null && !config.username().isBlank()) {
            sender.setUsername(config.username());
        }
        if (config.password() != null && !config.password().isBlank()) {
            sender.setPassword(config.password());
        }
        java.util.Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", Boolean.toString(config.smtpAuth()));
        props.put("mail.smtp.starttls.enable", Boolean.toString(config.starttls()));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        try {
            MimeMessage mimeMessage = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, StandardCharsets.UTF_8.name());
            helper.setFrom(config.fromEmail(), config.fromName());
            helper.setTo(message.toEmail());
            helper.setSubject(message.subject());
            helper.setText(message.htmlContent(), true);
            sender.send(mimeMessage);
        } catch (org.springframework.mail.MailException exception) {
            throw new EmailDeliveryException("SMTP delivery to " + message.toEmail() + " failed", exception);
        } catch (Exception exception) {
            throw new EmailDeliveryException("SMTP message could not be built", exception);
        }
    }
}
