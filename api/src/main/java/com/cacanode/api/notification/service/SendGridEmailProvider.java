package com.cacanode.api.notification.service;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;

@Component
public class SendGridEmailProvider implements EmailProvider {

    private final SendGrid sendGrid;
    private final String fromEmail;
    private final String fromName;

    public SendGridEmailProvider(
            SendGrid sendGrid,
            @Value("${app.email.from-email}") String fromEmail,
            @Value("${app.email.from-name:CacaNode}") String fromName) {
        this.sendGrid = sendGrid;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
    }

    @Override
    public String providerName() {
        return "SendGrid";
    }

    @Override
    public void send(EmailMessage message) {
        Mail mail = new Mail(
                new Email(fromEmail, fromName),
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
                        "SendGrid returned status %d: %s".formatted(response.getStatusCode(), response.getBody())
                );
            }
        } catch (IOException e) {
            throw new EmailDeliveryException("SendGrid request failed", e);
        }
    }
}
