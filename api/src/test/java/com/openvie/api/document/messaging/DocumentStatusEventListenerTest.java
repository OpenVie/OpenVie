// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.document.messaging;

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

import com.openvie.api.document.service.DocumentService;
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
                null,
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
