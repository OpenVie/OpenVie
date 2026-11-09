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

package com.openvie.api.chat.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.ai.api.AiInferenceApi;
import com.openvie.api.ai.api.AiInferenceApi.StructuredJsonRequest;
import com.openvie.api.ai.api.AiInferenceApi.StructuredJsonResponse;
import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.chat.service.BizAppSlotAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompleteJsonLocaleTest {

    private BizAppRuntimeApi bizAppRuntimeApi;
    private AiInferenceApi aiInferenceApi;
    private BizAppIntentRouter router;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bizAppRuntimeApi = mock(BizAppRuntimeApi.class);
        aiInferenceApi = mock(AiInferenceApi.class);
        router = new BizAppIntentRouter(bizAppRuntimeApi, aiInferenceApi, new ObjectMapper(),
                new BizAppSlotAgent(aiInferenceApi, new ObjectMapper()));
        router.setActionsEnabled(true);

        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of());
        when(bizAppRuntimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of());
    }

    @Test
    void systemPromptEmbedsVietnameseLocale() {
        when(aiInferenceApi.completeJson(any())).thenReturn(
                new StructuredJsonResponse("{\"intent\":\"NONE\",\"confidence\":0.0}", "model", 5, 5)
        );

        router.route(tenantId, orgId, actorId, "MEMBER", "cài đặt app crm", "vi-VN", sessionId);

        ArgumentCaptor<StructuredJsonRequest> captor = ArgumentCaptor.forClass(StructuredJsonRequest.class);
        verify(aiInferenceApi).completeJson(captor.capture());

        StructuredJsonRequest req = captor.getValue();
        assertNotNull(req.systemPrompt());
        assertTrue(req.systemPrompt().contains("Locale: vi-VN"));
    }

    @Test
    void systemPromptEmbedsEnglishLocale() {
        when(aiInferenceApi.completeJson(any())).thenReturn(
                new StructuredJsonResponse("{\"intent\":\"NONE\",\"confidence\":0.0}", "model", 5, 5)
        );

        router.route(tenantId, orgId, actorId, "MEMBER", "install crm app", "en-US", sessionId);

        ArgumentCaptor<StructuredJsonRequest> captor = ArgumentCaptor.forClass(StructuredJsonRequest.class);
        verify(aiInferenceApi).completeJson(captor.capture());

        StructuredJsonRequest req = captor.getValue();
        assertNotNull(req.systemPrompt());
        assertTrue(req.systemPrompt().contains("Locale: en-US"));
    }
}
