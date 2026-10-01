package com.cacanode.api.notification.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import brevo.ApiException;
import brevoApi.TransactionalEmailsApi;
import brevoModel.SendSmtpEmail;
import brevoModel.SendSmtpEmailSender;
import brevoModel.SendSmtpEmailTo;

@Component
public class BrevoEmailProvider implements EmailProvider {

    private final TransactionalEmailsApi transactionalEmailsApi;
    private final String fromEmail;
    private final String fromName;

    public BrevoEmailProvider(
            TransactionalEmailsApi transactionalEmailsApi,
            @Value("${app.email.from-email}") String fromEmail,
            @Value("${app.email.from-name:CacaNode}") String fromName) {
        this.transactionalEmailsApi = transactionalEmailsApi;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
    }

    @Override
    public String providerName() {
        return "Brevo";
    }

    @Override
    public void send(EmailMessage message) {
        SendSmtpEmail email = new SendSmtpEmail()
                .sender(new SendSmtpEmailSender()
                        .email(fromEmail)
                        .name(fromName))
                .to(List.of(new SendSmtpEmailTo()
                        .email(message.toEmail())
                        .name(message.toName())))
                .subject(message.subject())
                .htmlContent(message.htmlContent());

        try {
            transactionalEmailsApi.sendTransacEmail(email);
        } catch (ApiException e) {
            throw new EmailDeliveryException("Brevo request failed", e);
        }
    }
}
