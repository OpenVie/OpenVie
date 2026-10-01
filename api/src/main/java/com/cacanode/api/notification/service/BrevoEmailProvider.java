package com.cacanode.api.notification.service;

import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import brevo.ApiClient;
import brevo.ApiException;
import brevo.auth.ApiKeyAuth;
import brevoApi.TransactionalEmailsApi;
import brevoModel.SendSmtpEmail;
import brevoModel.SendSmtpEmailSender;
import brevoModel.SendSmtpEmailTo;

/**
 * Brevo transport. The API key arrives per delivery from the organization's
 * channel row (or the environment default), so the client is built per send.
 */
@Component
public class BrevoEmailProvider implements EmailProvider {

    private final Function<String, TransactionalEmailsApi> clientFactory;

    public BrevoEmailProvider() {
        this(BrevoEmailProvider::clientForApiKey);
    }

    /** Test seam: builds the API client from a per-delivery key. */
    BrevoEmailProvider(Function<String, TransactionalEmailsApi> clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public String type() {
        return "brevo";
    }

    @Override
    public String providerName() {
        return "Brevo";
    }

    @Override
    public boolean available(NotificationChannelConfig config) {
        return config != null && config.apiKey() != null && !config.apiKey().isBlank();
    }

    @Override
    public void send(EmailMessage message, NotificationChannelConfig config) {
        if (!available(config)) {
            throw new EmailDeliveryException(
                    "Brevo is selected but no API key is configured; set BREVO_API_KEY"
                    + " or store the key in an organization channel");
        }
        TransactionalEmailsApi api = clientFactory.apply(config.apiKey());

        SendSmtpEmail email = new SendSmtpEmail()
                .sender(new SendSmtpEmailSender()
                        .email(config.fromEmail())
                        .name(config.fromName()))
                .to(List.of(new SendSmtpEmailTo()
                        .email(message.toEmail())
                        .name(message.toName())))
                .subject(message.subject())
                .htmlContent(message.htmlContent());

        try {
            api.sendTransacEmail(email);
        } catch (ApiException e) {
            throw new EmailDeliveryException("Brevo request failed", e);
        }
    }

    private static TransactionalEmailsApi clientForApiKey(String apiKey) {
        ApiClient client = new ApiClient();
        ((ApiKeyAuth) client.getAuthentication("api-key")).setApiKey(apiKey);
        return new TransactionalEmailsApi(client);
    }
}
