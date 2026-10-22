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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.workflow.service.SensitivityPolicyService;

/**
 * The dual-consumption guard of the sensitivity flag contract (issue #8):
 * the runtime policy copy inside the API must stay byte-identical to the
 * canonical contract every language side validates against — Java keeps no
 * second keyword list of its own.
 */
class SensitivityPolicyContractSyncTest {

    private static final Path CONTRACT = Path.of("..", "contracts", "approval", "v1");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void runtimeCopyIsByteIdenticalToTheCanonicalContract() throws Exception {
        byte[] canonical = Files.readAllBytes(CONTRACT.resolve("sensitive-policy.json"));
        byte[] runtime = getClass().getResourceAsStream(
                "/approval/sensitive-policy-v1.json").readAllBytes();
        assertNotEquals(0, runtime.length, "runtime policy copy must exist on the classpath");
        assertTrue(java.util.Arrays.equals(canonical, runtime),
                "approval/sensitive-policy-v1.json must be a byte-identical copy of contracts/approval/v1/sensitive-policy.json");
    }

    @Test
    void contractStaysUsableAndAgainstsTheLoadedScorer() throws Exception {
        JsonNode contract = mapper.readTree(Files.readString(CONTRACT.resolve("sensitive-policy.json")));

        assertEquals("1.0", contract.path("schema_version").asText());
        assertEquals("sensitive-keyword-v1", contract.path("policy_version").asText(),
                "the contract must pin the single POLICY_VERSION marker the audit stores");
        Set<String> terms = new HashSet<>();
        contract.path("terms").forEach(term -> terms.add(term.asText()));
        assertFalse(terms.isEmpty(), "the shared vocabulary must not be emptied");
        contract.path("fixtures").forEach(fixture -> assertTrue(
                fixture.has("text") && fixture.has("expected_sensitive"),
                "every fixture needs text and expected_sensitive"));

        SensitivityPolicyService policy = new SensitivityPolicyService(mapper);
        policy.loadPolicy();
        assertEquals("sensitive-keyword-v1", policy.getPolicyVersion());
        contract.path("fixtures").forEach(fixture -> {
            boolean expected = fixture.path("expected_sensitive").asBoolean();
            boolean actual = policy.isSensitive(fixture.path("text").asText());
            assertEquals(expected, actual,
                    "scorer must agree with the canonical fixture: " + fixture.path("text").asText());
        });
    }
}
