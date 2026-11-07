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

package com.openvie.api.bizapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.ai.api.AiInferenceApi;
import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.model.BizAppDraft;
import com.openvie.api.bizapp.repository.BizAppDraftRepository;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies the deterministic backstop around AI-generated BizApp manifests:
 * the small generator model may invent asset references that do not ship with
 * the platform (e.g. a bespoke approval BPMN file), which would deadlock the
 * app at install time. Draft normalization must clamp workflow blocks onto
 * the bundled asset catalog while leaving unrelated blocks untouched.
 */
class BizAppDraftGenerationClampTest {

    private static final String APPROVAL_JSON = """
            {"schema":"bizapp.v1","kind":"leave-management","version":1,
             "name":{"en":"Leave Management","vi":"Quản lý nghỉ phép"},
             "storage":"jsonb",
             "entities":[{"name":"leave_requests","label":{"en":"Leave Requests","vi":"Đơn nghỉ phép"},
               "fields":[{"name":"email","type":"string","required":true,"label":{"en":"Email","vi":"Email"}}],
               "hooks":{"onCreate":["roleApproval"],"onDecide":["notify1"]}}],
             "rbac":{"read":["ORG_OWNER","WORKSPACE_ADMIN","MEMBER"],"write":["ORG_OWNER","WORKSPACE_ADMIN"]},
             "blocks":[
               {"id":"approval1","type":"approval_workitem","params":{
                 "bpmnTemplate":"processes/leave-management-single-approval.bpmn20.xml",
                 "candidateRole":"EXECUTIVE_BOARD"}},
               {"id":"roleApproval","type":"approval_workitem","params":{
                 "bpmnTemplate":"processes/eoffice-document-approval.bpmn20.xml",
                 "candidateRole":"WORKSPACE_ADMIN"}},
               {"id":"notify1","type":"notify","params":{
                 "to":["creator","record.email"],"title":"Approved","subject":"Approved"}}]}
            """;

    private BizAppDraftRepository draftRepository;
    private AiInferenceApi aiInferenceApi;
    private BizAppProperties properties;
    private BizAppRuntime runtime;
    private BizAppDraftService service;

    @BeforeEach
    void setUp() {
        draftRepository = mock(BizAppDraftRepository.class);
        aiInferenceApi = mock(AiInferenceApi.class);
        properties = mock(BizAppProperties.class);
        runtime = mock(BizAppRuntime.class);

        when(properties.aiDraftingEnabled()).thenReturn(true);
        when(draftRepository.save(any(BizAppDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new BizAppDraftService(
                draftRepository,
                mock(BizAppInstallationRepository.class),
                new BizAppManifestValidator(),
                new BizAppManifestParser(new ObjectMapper()),
                aiInferenceApi,
                properties,
                runtime,
                new ObjectMapper()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateApprovalBlocksCollapseToOneAndHooksAreRepointed() {
        when(aiInferenceApi.completeJson(any(AiInferenceApi.StructuredJsonRequest.class))).thenReturn(
                new AiInferenceApi.StructuredJsonResponse(APPROVAL_JSON, "vylinh", 64, 16));

        var resp = service.generateDraftFromChat(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "Quản lý nghỉ phép", List.of());

        List<Map<String, Object>> blocks = (List<Map<String, Object>>) resp.manifest().get("blocks");
        assertEquals(2, blocks.size(), "only one approval_workitem block may survive normalization");
        assertEquals("approval1", blocks.get(0).get("id"), "the first approval block is the one kept");

        Map<String, Object> approvalParams = blockParams(blocks.get(0));
        assertEquals("processes/bizapp-single-approval.bpmn20.xml", approvalParams.get("bpmnTemplate"),
                "the invented BPMN path must be rewritten to the bundled single-approval template");
        assertEquals("WORKSPACE_ADMIN", approvalParams.get("candidateRole"),
                "candidate roles outside APPROVABLE_ROLES must clamp to WORKSPACE_ADMIN");

        assertEquals("notify1", blocks.get(1).get("id"));
        Map<String, Object> notify = blockParams(blocks.get(1));
        assertEquals(List.of("creator", "record.email"), notify.get("to"), "unrelated blocks stay untouched");
        assertEquals("Approved", notify.get("title"));

        List<Map<String, Object>> entities = (List<Map<String, Object>>) resp.manifest().get("entities");
        Map<String, Object> hooks = (Map<String, Object>) entities.get(0).get("hooks");
        assertEquals(List.of("approval1"), hooks.get("onCreate"),
                "a hook targeting a dropped approval block must point at the kept one");
        assertEquals(List.of("notify1"), hooks.get("onDecide"));
    }

    @Test
    void repairLoopIsNotNeededWhenOnlyAssetPathsAreInvented() {
        // The clamp runs before validation, so a manifest whose ONLY defect is
        // an invented asset path validates on the first attempt (no extra LLM
        // repair round-trips).
        when(aiInferenceApi.completeJson(any(AiInferenceApi.StructuredJsonRequest.class))).thenReturn(
                new AiInferenceApi.StructuredJsonResponse(APPROVAL_JSON, "vylinh", 64, 16));

        var resp = service.generateDraftFromChat(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "Quản lý nghỉ phép", List.of());

        assertEquals(Boolean.TRUE, resp.validation().ok(),
                "clamped manifest must pass the stateless validator on the first generation attempt");
        assertEquals(1, aiInvocations(), "repair loop must not re-invoke the model");
    }

    private int aiInvocations() {
        ArgumentCaptor<AiInferenceApi.StructuredJsonRequest> captor =
                ArgumentCaptor.forClass(AiInferenceApi.StructuredJsonRequest.class);
        verify(aiInferenceApi, times(1)).completeJson(captor.capture());
        return 1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> blockParams(Map<String, Object> block) {
        return (Map<String, Object>) block.get("params");
    }
}
