package com.cacanode.api.notification.service;

import java.nio.charset.StandardCharsets;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Configured SMTP path for self-hosted installs (a local mail catcher in dev,
 * the operator's relay in production). The {@link JavaMailSender} bean only
 * exists when {@code spring.mail.host} is configured, so an unconfigured host
 * fails loudly instead of silently skipping delivery.
 */
@Component
public class SmtpEmailProvider implements EmailProvider {

    private final ObjectProvider<JavaMailSender> javaMailSender;
    private final String fromEmail;
    private final String fromName;

    public SmtpEmailProvider(
            ObjectProvider<JavaMailSender> javaMailSender,
            @Value("${app.email.from-email}") String fromEmail,
            @Value("${app.email.from-name:CacaNode}") String fromName) {
        this.javaMailSender = javaMailSender;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
    }

    @Override
    public String providerName() {
        return "SMTP";
    }

    @Override
    public void send(EmailMessage message) {
        JavaMailSender sender = javaMailSender.getIfAvailable();
        if (sender == null) {
            throw new EmailDeliveryException(
                    "SMTP mail is selected (app.email.provider=smtp) but spring.mail.host is not configured;"
                            + " set MAIL_HOST to your SMTP host or mail catcher");
        }

        try {
            MimeMessage mimeMessage = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, StandardCharsets.UTF_8.name());
            helper.setFrom(fromEmail, fromName);
            helper.setTo(message.toEmail());
            helper.setSubject(message.subject());
            helper.setText(message.htmlContent(), true);
            sender.send(mimeMessage);
        } catch (MailException e) {
            throw new EmailDeliveryException("SMTP delivery to " + message.toEmail() + " failed", e);
        } catch (Exception e) {
            throw new EmailDeliveryException("SMTP message could not be built", e);
        }
    }
}
