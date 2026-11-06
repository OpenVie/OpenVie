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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

/**
 * Verifies Invariant I-6 & DoD #5: Kill-switch disabled state introduces zero
 * side-effects or extra latency to document queries.
 */
class RecordKillSwitchNeutralityTest {

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
    }

    @Test
    void disabledRecordKillSwitchShortCircuitsBeforeGateAndCallsZeroModels() {
        // Both actions and records disabled
        router.setActionsEnabled(false);
        router.setRecordActionsEnabled(false);

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Thêm khách hàng Nguyễn Văn An vào CRM", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.NONE, result.intent());
        assertNull(result.actionCard());
        verifyNoInteractions(aiInferenceApi);
        verifyNoInteractions(runtimeApi);
    }

    @Test
    void writeQueryWithDisabledRecordActionsReturnsNoneWithoutActionCard() {
        // App actions enabled (for install/uninstall), but record actions disabled
        router.setActionsEnabled(true);
        router.setRecordActionsEnabled(false);

        InstalledAppSummary crm = new InstalledAppSummary(
                UUID.randomUUID(), "crm", "CRM Lite", 1, "Quản lý khách hàng",
                List.of("leads"), List.of(), "ACTIVE"
        );
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(crm));
        when(runtimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of());

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Thêm khách hàng Nguyễn Văn An vào CRM", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.NONE, result.intent());
        assertNull(result.actionCard());
    }
}
