package com.cacanode.api.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
        provider = new BrevoEmailProvider(transactionalEmailsApi, "from@example.com", "CacaNode");
    }

    @Test
    void buildsExpectedTransactionalEmailPayload() throws ApiException {
        provider.send(message());

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
        doThrow(new ApiException("brevo error"))
                .when(transactionalEmailsApi)
                .sendTransacEmail(org.mockito.ArgumentMatchers.any(SendSmtpEmail.class));

        assertThrows(EmailDeliveryException.class, () -> provider.send(message()));
    }

    private EmailMessage message() {
        return new EmailMessage("user@example.com", "Ada Lovelace", "Subject", "<p>Hello</p>");
    }
}
