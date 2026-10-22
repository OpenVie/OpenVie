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

package com.openvie.api.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.workflow.service.SensitivityPolicyService;
import com.openvie.api.workflow.service.SensitivityScoringDelegate;

/**
 * The BPMN service task writes exactly the variables the routing gateway and
 * the audit consumption rely on: sensitive, approvalLevel, policyVersion.
 */
class SensitivityScoringDelegateTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DelegateExecution execution = mock(DelegateExecution.class);
    private final Map<String, Object> variables = new HashMap<>();
    private SensitivityScoringDelegate delegate;

    @BeforeEach
    void setUp() {
        SensitivityPolicyService policy = new SensitivityPolicyService(mapper);
        policy.loadPolicy();
        delegate = new SensitivityScoringDelegate(policy);
        when(execution.getVariable("documentFileName")).thenAnswer(
                invocation -> variables.get("documentFileName"));
        when(execution.getVariable("publishTarget")).thenAnswer(
                invocation -> variables.get("publishTarget"));
        when(execution.getVariable("documentId")).thenAnswer(
                invocation -> variables.get("documentId"));
        doAnswer(invocation -> {
            variables.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(execution).setVariable(ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    void sensitiveFileNameRequiresLevelTwoAndRecordsThePolicyVersion() {
        // Multi-word terms require whitespace, exactly like the Python scorer
        // (fixtures-verified): "hop-dong" hyphenated does NOT match "hop dong".
        variables.put("documentFileName", "hop dong lao dong bien ban.pdf");
        variables.put("publishTarget", "workspace");

        delegate.execute(execution);

        assertEquals(Boolean.TRUE, variables.get("sensitive"));
        assertEquals(2, variables.get("approvalLevel"));
        assertEquals("sensitive-keyword-v1", variables.get("policyVersion"));
    }

    @Test
    void ordinaryWorkspaceDocumentRoutesToLevelOne() {
        variables.put("documentFileName", "nasdaq onboarding checklist.pdf");
        variables.put("publishTarget", "workspace");

        delegate.execute(execution);

        assertEquals(Boolean.FALSE, variables.get("sensitive"));
        assertEquals(1, variables.get("approvalLevel"));
        assertEquals("sensitive-keyword-v1", variables.get("policyVersion"));
    }

    @Test
    void sharedTargetEscalatesEvenWithoutSensitiveWords() {
        variables.put("documentFileName", "onboarding checklist.pdf");
        variables.put("publishTarget", "shared");

        delegate.execute(execution);

        assertEquals(Boolean.FALSE, variables.get("sensitive"));
        assertEquals(2, variables.get("approvalLevel"));
    }

    @Test
    void diacriticVetnameseFileNameIsScoredAfterFolding() {
        variables.put("documentFileName", "Hợp đồng tài chính nội bộ.pdf");
        variables.put("publishTarget", "workspace");

        delegate.execute(execution);

        assertEquals(Boolean.TRUE, variables.get("sensitive"));
    }

    @Test
    void alsoKeepsTheGatewayOrderWhenExecutedTwice() {
        variables.put("documentFileName", "privacy notice.pdf");
        variables.put("publishTarget", "public");
        delegate.execute(execution);
        assertEquals(2, variables.get("approvalLevel"));

        variables.put("documentFileName", "weekly standup notes.pdf");
        variables.put("publishTarget", "workspace");
        delegate.execute(execution);
        assertEquals(1, variables.get("approvalLevel"));
    }
}
