package com.cacanode.api.notification.service;

import java.io.IOException;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;

/**
 * SendGrid transport. The API key arrives per delivery from the organization's
 * channel row (or the environment default), so the client is built per send.
 */
@Component
public class SendGridEmailProvider implements EmailProvider {

    private final Function<String, SendGrid> clientFactory;

    public SendGridEmailProvider() {
        this(SendGrid::new);
    }

    /** Test seam: builds the API client from a per-delivery key. */
    SendGridEmailProvider(Function<String, SendGrid> clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public String type() {
        return "sendgrid";
    }

    @Override
    public String providerName() {
        return "SendGrid";
    }

    @Override
    public boolean available(NotificationChannelConfig config) {
        return config != null && config.apiKey() != null && !config.apiKey().isBlank();
    }

    @Override
    public void send(EmailMessage message, NotificationChannelConfig config) {
        if (!available(config)) {
            throw new EmailDeliveryException(
                    "SendGrid is selected but no API key is configured; set SENDGRID_API_KEY"
                    + " or store the key in an organization channel");
        }
        SendGrid sendGrid = clientFactory.apply(config.apiKey());
        Mail mail = new Mail(
                new Email(config.fromEmail(), config.fromName()),
                message.subject(),
                new Email(message.toEmail(), message.toName()),
                new Content("text/html", message.htmlContent())
        );

        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGrid.api(request);
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw new EmailDeliveryException(
                        "SendGrid returned status %d: %s".formatted(
                                response.getStatusCode(), response.getBody()));
            }
        } catch (IOException e) {
            throw new EmailDeliveryException("SendGrid request failed", e);
        }
    }
}
