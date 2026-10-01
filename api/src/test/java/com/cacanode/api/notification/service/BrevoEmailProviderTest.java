package com.cacanode.api.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import brevo.ApiException;
import brevoApi.TransactionalEmailsApi;
import brevoModel.SendSmtpEmail;

class BrevoEmailProviderTest {

    private TransactionalEmailsApi transactionalEmailsApi;
    private BrevoEmailProvider provider;

    @BeforeEach
    void setUp() {
        transactionalEmailsApi = mock(TransactionalEmailsApi.class);
        provider = new BrevoEmailProvider(key -> transactionalEmailsApi);
    }

    @Test
    void buildsExpectedTransactionalEmailPayload() throws ApiException {
        provider.send(message(), config("key"));

        ArgumentCaptor<SendSmtpEmail> emailCaptor = ArgumentCaptor.forClass(SendSmtpEmail.class);
        verify(transactionalEmailsApi).sendTransacEmail(emailCaptor.capture());

        SendSmtpEmail email = emailCaptor.getValue();
        assertEquals("from@example.com", email.getSender().getEmail());
        assertEquals("CacaNode", email.getSender().getName());
        assertEquals("user@example.com", email.getTo().get(0).getEmail());
        assertEquals("Ada Lovelace", email.getTo().get(0).getName());
        assertEquals("Subject", email.getSubject());
        assertEquals("<p>Hello</p>", email.getHtmlContent());
    }

    @Test
    void wrapsApiExceptionAsDeliveryException() throws ApiException {
        org.mockito.Mockito.doThrow(new ApiException("provider down"))
                .when(transactionalEmailsApi).sendTransacEmail(any(SendSmtpEmail.class));

        assertThrows(EmailDeliveryException.class, () -> provider.send(message(), config("key")));
    }

    @Test
    void missingApiKeyIsUnavailableAndFailsLoudly() {
        assertFalse(provider.available(config("")));
        EmailDeliveryException exception = assertThrows(EmailDeliveryException.class,
                () -> provider.send(message(), config("  ")));
        assertTrue(exception.getMessage().contains("BREVO_API_KEY"),
                "the failure must name the missing variable: " + exception.getMessage());
    }

    private NotificationChannelConfig config(String apiKey) {
        return new NotificationChannelConfig(
                "brevo", "from@example.com", "CacaNode", "", 587, "", "",
                false, false, apiKey);
    }

    private EmailMessage message() {
        return new EmailMessage("user@example.com", "Ada Lovelace", "Subject", "<p>Hello</p>");
    }
}
