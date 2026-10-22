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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.workflow.service.SensitivityPolicyService;

/**
 * Behavioral checks of the Java scorer against the shared vocabulary:
 * whole-word matches, whitespace-tolerant multi-word terms, Vietnamese
 * accent folding, and the level decision routing of the process gateway.
 */
class SensitivityPolicyServiceTest {

    private final SensitivityPolicyService policy =
            new SensitivityPolicyService(new ObjectMapper());

    private SensitivityPolicyService loaded() {
        policy.loadPolicy();
        return policy;
    }

    @Test
    void matchesSingleWordTermsAsWholeWords() {
        SensitivityPolicyService loaded = loaded();
        assertTrue(loaded.isSensitive("do we need a lawyer for this contract"));
        assertFalse(loaded.isSensitive("did he show the data or shove it"));
        assertTrue(loaded.isSensitive("submit the tax report"));
        assertFalse(loaded.isSensitive("supply staplers"));
    }

    @Test
    void matchesMultiWordTermsAcrossWhitespaceAndNewlines() {
        SensitivityPolicyService loaded = loaded();
        assertTrue(loaded.isSensitive("annual interest\trate is set today"));
        assertTrue(loaded.isSensitive("lai suat vay von"));
    }

    @Test
    void foldsVietnameseDiacriticsBeforeMatching() {
        SensitivityPolicyService loaded = loaded();
        assertTrue(loaded.isSensitive("Tôi có phải nộp thuế không?"));
        assertTrue(loaded.isSensitive("Bệnh của tôi có cần thuốc đặc biệt không?"));
        assertTrue(loaded.isSensitive("Hợp đồng lao động"));
        assertFalse(loaded.isSensitive("báo cáo mạng nội bộ tuần 42"));
    }

    @Test
    void blankAndNullTextsAreNeverSensitive() {
        SensitivityPolicyService loaded = loaded();
        assertFalse(loaded.isSensitive(null));
        assertFalse(loaded.isSensitive(""));
        assertFalse(loaded.isSensitive("   "));
    }

    @Test
    void levelDecisionRoutesSharedPublicAndSensitiveToLevelTwo() {
        SensitivityPolicyService loaded = loaded();
        assertEquals(1, loaded.decisionLevel(false, "workspace"));
        assertEquals(2, loaded.decisionLevel(true, "workspace"),
                "a sensitive hit needs the organization owner");
        assertEquals(2, loaded.decisionLevel(false, "shared"),
                "shared publication target needs the organization owner");
        assertEquals(2, loaded.decisionLevel(false, "public"),
                "public publication target needs the organization owner");
        assertEquals(2, loaded.decisionLevel(true, "PUBLIC"));
    }

    @Test
    void exposesTheSingleSharedPolicyVersionMarker() {
        SensitivityPolicyService loaded = loaded();
        assertEquals("sensitive-keyword-v1", loaded.getPolicyVersion());
        assertTrue(loaded.getTerms().contains("hop dong"),
                "the runtime copy carries the shared Vietnamese transliterated vocabulary");
        assertFalse(loaded.getTerms().isEmpty());
    }
}
