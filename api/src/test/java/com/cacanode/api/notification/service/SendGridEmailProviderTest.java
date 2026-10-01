package com.cacanode.api.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        provider = new SendGridEmailProvider(sendGrid, "from@example.com", "CacaNode");
    }

    @Test
    void treats2xxResponseAsSuccess() throws IOException {
        when(sendGrid.api(any(Request.class))).thenReturn(new Response(202, "", Map.of()));

        provider.send(message());

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(sendGrid).api(requestCaptor.capture());
        Request request = requestCaptor.getValue();
        assertEquals(Method.POST, request.getMethod());
        assertEquals("mail/send", request.getEndpoint());
    }

    @Test
    void treatsNon2xxResponseAsFailure() throws IOException {
        when(sendGrid.api(any(Request.class))).thenReturn(new Response(400, "bad request", Map.of()));

        assertThrows(EmailDeliveryException.class, () -> provider.send(message()));
    }

    @Test
    void wrapsIOExceptionAsDeliveryException() throws IOException {
        when(sendGrid.api(any(Request.class))).thenThrow(new IOException("network error"));

        assertThrows(EmailDeliveryException.class, () -> provider.send(message()));
    }

    private EmailMessage message() {
        return new EmailMessage("user@example.com", "Ada Lovelace", "Subject", "<p>Hello</p>");
    }
}
