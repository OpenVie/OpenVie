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
import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.chat.service.BizAppSlotAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies RAG neutrality: Questions about procedures, guidelines, or policies
 * containing write verbs or app names must route to regular document RAG (NONE)
 * without generating action cards.
 */
class AppMentioningDocumentQuestionTest {

    private BizAppRuntimeApi runtimeApi;
    private AiInferenceApi aiInferenceApi;
    private BizAppIntentRouter router;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        runtimeApi = mock(BizAppRuntimeApi.class);
        aiInferenceApi = mock(AiInferenceApi.class);
        router = new BizAppIntentRouter(runtimeApi, aiInferenceApi, new ObjectMapper(),
                new BizAppSlotAgent(aiInferenceApi, new ObjectMapper()));
        router.setActionsEnabled(true);
        router.setRecordActionsEnabled(true);

        InstalledAppSummary crm = new InstalledAppSummary(
                UUID.randomUUID(), "crm", "CRM Lite", 1, "Quản lý khách hàng",
                List.of("leads"), List.of(), "ACTIVE"
        );
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(crm));
        when(runtimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of());
    }

    @Test
    void procedureAndPolicyQuestionsRouteToNoneRAG() {
        List<String> procedureQueries = List.of(
                "Quy trình thêm khách hàng mới vào CRM gồm những bước nào?",
                "Hướng dẫn sửa thông tin khách hàng trong hệ thống?",
                "Chính sách xoá dữ liệu khách hàng quy định như thế nào?",
                "Làm sao để tạo một hợp đồng mới theo đúng thủ tục?",
                "Quy định về việc phê duyệt đề xuất ra sao?"
        );

        for (String query : procedureQueries) {
            var result = router.route(tenantId, orgId, actorId, "MEMBER", query, "vi-VN", sessionId);
            assertEquals(BizAppIntentRouter.Intent.NONE, result.intent(),
                    "Query '" + query + "' must route to document RAG (NONE)");
            assertNull(result.actionCard(),
                    "Procedure question must not produce an action card");
        }
    }
}
