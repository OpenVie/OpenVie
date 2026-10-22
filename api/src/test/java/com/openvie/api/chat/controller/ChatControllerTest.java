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

package com.openvie.api.chat.controller;

import com.openvie.api.chat.dto.ChatDtos;
import com.openvie.api.ai.api.AiInferenceException;
import com.openvie.api.chat.query.ChatControlPlaneService;
import com.openvie.api.common.controller.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatControllerTest extends BaseController {
    private final ChatControlPlaneService service = mock(ChatControlPlaneService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ChatController controller = new ChatController(service);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(request.getAttribute("tenantId")).thenReturn(tenantId.toString());
        when(request.getAttribute("userId")).thenReturn(userId.toString());
    }

    @Test
    void jsonAcceptRunsTheSamePipelineSynchronouslyWithoutStreaming() {
        UUID sessionId = UUID.randomUUID();
        when(service.submitEmployeeMessage(eq(tenantId), eq(userId), eq(sessionId), eq("Question"),
                any(), any(), eq("key"), eq("request"), any()))
                .thenReturn(new ChatDtos.AssistantMessageResponse("assistant", "Committed answer", List.of(), null));

        when(request.getHeader("Accept")).thenReturn(MediaType.APPLICATION_JSON_VALUE);
        var body = new ChatDtos.SubmitMessageRequest("Question", Map.of(), null);

        var response = controller.submit(sessionId, body, "key", "request", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        var assistant = (ChatDtos.AssistantMessageResponse) response.getBody();
        assertEquals("assistant", assistant.role());
        assertEquals("Committed answer", assistant.content());
    }

    @Test
    void jsonAcceptWithWildcardlessEventStreamPreferenceStaysStreaming() throws Exception {
        when(request.getHeader("Accept")).thenReturn(null);
        UUID sessionId = UUID.randomUUID();
        when(service.submitEmployeeMessage(eq(tenantId), eq(userId), eq(sessionId), eq("Question"),
                any(), any(), isNull(), isNull(), any()))
                .thenReturn(new ChatDtos.AssistantMessageResponse("assistant", "Committed answer", List.of(), null));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try {
            var pending = mvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                            .requestAttr("tenantId", tenantId.toString())
                            .requestAttr("userId", userId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"Question\",\"metadata\":{}}"))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            pending.getAsyncResult(3000);
            mvc.perform(asyncDispatch(pending));
        } finally {
            controller.closeStreams();
        }
    }

    @Test
    void metadataHeadersAreReturnedWithoutChangingListBodies() {
        when(service.playgroundPage(tenantId, userId, 30, 0, null, null, null,
                null, null, null, null))
                .thenReturn(new ChatControlPlaneService.PlaygroundPage(List.of(), "opaque-token"));

        var playground = controller.playgroundResponse(30, 0, null, null, null,
                null, null, null, null, request);

        assertEquals("opaque-token", playground.getHeaders().getFirst("X-Next-Cursor"));
        assertEquals(List.of(), playground.getBody());
    }

    @Test
    void streamCarriesReplacementPreviewsResetAndAuthoritativeComplete() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(service.submitEmployeeMessage(eq(tenantId), eq(userId), eq(sessionId), eq("Question"),
                any(), any(), eq("key"), eq("request"), any())).thenAnswer(invocation -> {
            ChatControlPlaneService.AnswerStreamListener listener = invocation.getArgument(8);
            listener.onContent("Initial draft");
            listener.onReset();
            listener.onContent("Revised draft");
            return new ChatDtos.AssistantMessageResponse("assistant", "Committed answer", List.of(), null);
        });
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try {
            var pending = mvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                            .requestAttr("tenantId", tenantId.toString())
                            .requestAttr("userId", userId.toString())
                            .header("Idempotency-Key", "key")
                            .header("X-Request-ID", "request")
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM)
                            .content("{\"content\":\"Question\",\"metadata\":{}}"))
                    .andExpect(request().asyncStarted()).andReturn();
            pending.getAsyncResult(3000);
            String body = mvc.perform(asyncDispatch(pending))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(header().string("Cache-Control", containsString("no-cache")))
                    .andExpect(header().string("Cache-Control", containsString("no-transform")))
                    .andExpect(header().string("X-Accel-Buffering", "no"))
                    .andReturn().getResponse().getContentAsString();

            assertTrue(body.contains("event:content"));
            assertTrue(body.contains("data:{\"content\":\"Initial draft\"}"));
            assertTrue(body.indexOf("event:reset") > body.indexOf("Initial draft"));
            assertTrue(body.indexOf("Revised draft") > body.indexOf("event:reset"));
            assertTrue(body.indexOf("event:complete") > body.indexOf("Revised draft"));
            assertTrue(body.contains("\"content\":\"Committed answer\""));
            assertFalse(body.contains("event:error"));
        } finally {
            controller.closeStreams();
        }
    }

    @Test
    void establishedStreamKeepsMappedProviderErrorCodeAndClearsDraftWithoutComplete() throws Exception {
        when(service.submitEmployeeMessage(any(), any(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    ChatControlPlaneService.AnswerStreamListener listener = invocation.getArgument(8);
                    listener.onContent("Uncommitted draft");
                    throw new AiInferenceException(HttpStatus.GATEWAY_TIMEOUT, "MODEL_TIMEOUT",
                            "The model took too long to answer.");
                });
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try {
            var pending = mvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", UUID.randomUUID())
                            .requestAttr("tenantId", tenantId.toString())
                            .requestAttr("userId", userId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM)
                            .content("{\"content\":\"Question\",\"metadata\":{}}"))
                    .andExpect(request().asyncStarted()).andReturn();
            pending.getAsyncResult(3000);
            String body = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertTrue(body.contains("event:error"));
            assertTrue(body.contains("\"code\":\"MODEL_TIMEOUT\""));
            assertTrue(body.contains("\"message\":\"The model took too long to answer.\""));
            assertFalse(body.contains("event:complete"));
        } finally {
            controller.closeStreams();
        }
    }
}
