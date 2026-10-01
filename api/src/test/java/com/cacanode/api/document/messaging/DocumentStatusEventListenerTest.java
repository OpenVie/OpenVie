package com.cacanode.api.document.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

import com.cacanode.api.document.service.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;

class DocumentStatusEventListenerTest {

    @Mock
    private DocumentService documentService;

    private DocumentStatusEventListener listener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new DocumentStatusEventListener(new ObjectMapper(), documentService);
    }

    @Test
    void appliesValidStatusEvent() throws Exception {
        DocumentStatusEvent event = new DocumentStatusEvent(
                "1.0",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "PROCESSING",
                null,
                null
        );
        when(documentService.applyStatusEvent(event)).thenReturn(true);

        listener.onMessage(jsonMessage(new ObjectMapper().writeValueAsBytes(event)));

        verify(documentService).applyStatusEvent(event);
    }

    @Test
    void acknowledgesMalformedEventWithoutThrowing() {
        Message message = jsonMessage("{not-json".getBytes());

        assertDoesNotThrow(() -> listener.onMessage(message));
        verifyNoInteractions(documentService);
    }

    private Message jsonMessage(byte[] body) {
        return MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
    }
}
