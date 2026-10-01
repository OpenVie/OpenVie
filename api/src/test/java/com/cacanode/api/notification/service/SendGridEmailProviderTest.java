package com.cacanode.api.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;

class SendGridEmailProviderTest {

    private SendGrid sendGrid;
    private SendGridEmailProvider provider;

    @BeforeEach
    void setUp() {
        sendGrid = mock(SendGrid.class);
        provider = new SendGridEmailProvider(key -> sendGrid);
    }

    @Test
    void treats2xxResponseAsSuccess() throws IOException {
        when(sendGrid.api(any(Request.class))).thenReturn(new Response(202, "", Map.of()));

        provider.send(message(), config("key-123"));

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(sendGrid).api(requestCaptor.capture());
        Request request = requestCaptor.getValue();
        assertEquals(Method.POST, request.getMethod());
        assertEquals("mail/send", request.getEndpoint());
    }

    @Test
    void non2xxResponseIsFailure() throws IOException {
        when(sendGrid.api(any(Request.class))).thenReturn(new Response(400, "bad request", Map.of()));

        assertThrows(EmailDeliveryException.class, () -> provider.send(message(), config("key")));
    }

    @Test
    void wrapsIOExceptionAsDeliveryException() throws IOException {
        when(sendGrid.api(any(Request.class))).thenThrow(new IOException("network error"));

        assertThrows(EmailDeliveryException.class, () -> provider.send(message(), config("key")));
    }

    @Test
    void missingApiKeyIsUnavailableAndFailsLoudly() {
        assertFalse(provider.available(config("")));
        EmailDeliveryException exception = assertThrows(EmailDeliveryException.class,
                () -> provider.send(message(), config("  ")));
        assertTrue(exception.getMessage().contains("SENDGRID_API_KEY"),
                "the failure must name the missing variable: " + exception.getMessage());
    }

    @Test
    void senderComesFromTheChannelConfig() throws IOException {
        when(sendGrid.api(any(Request.class))).thenReturn(new Response(202, "", Map.of()));

        provider.send(message(), config("key"));

        // The per-delivery config (not a startup bean) decides the sender.
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(sendGrid).api(captor.capture());
        assertTrue(captor.getValue().getBody().contains("from@example.com"));
    }

    private NotificationChannelConfig config(String apiKey) {
        return new NotificationChannelConfig(
                "sendgrid", "from@example.com", "CacaNode", "", 587, "", "",
                false, false, apiKey);
    }

    private EmailMessage message() {
        return new EmailMessage("user@example.com", "Ada Lovelace", "Subject", "<p>Hello</p>");
    }
}
