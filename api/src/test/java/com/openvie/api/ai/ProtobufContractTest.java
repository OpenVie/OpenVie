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

package com.openvie.api.ai;

import com.openvie.ai.v1.OpenVieAiProto;
import com.google.protobuf.Descriptors;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtobufContractTest {
    @Test
    void inferenceServiceExposesOnlyTheThreeRagMethods() {
        var service = OpenVieAiProto.getDescriptor().findServiceByName("InferenceService");
        assertEquals(List.of(
                "GenerateAnswer",
                "ListDocumentUnits",
                "DeleteDocumentIndex"),
                service.getMethods().stream().map(Descriptors.MethodDescriptor::getName).toList());
        var generate = service.findMethodByName("GenerateAnswer");
        assertFalse(generate.isClientStreaming());
        assertTrue(generate.isServerStreaming());
        assertEquals("GenerateAnswerEvent", generate.getOutputType().getName());
    }

    @Test
    void generateAnswerFieldsAreFrozen() {
        assertFields("GenerateAnswerRequest", Map.ofEntries(
                Map.entry("generation_id", 1), Map.entry("turn_id", 2),
                Map.entry("tenant_id", 3), Map.entry("chatbot_id", 4),
                Map.entry("knowledge_base_id", 5), Map.entry("authoritative_revision", 6),
                Map.entry("channel", 7), Map.entry("locale", 8), Map.entry("question", 9),
                Map.entry("prior_messages", 10), Map.entry("tenant_name", 11),
                Map.entry("prompt_schema_version", 12), Map.entry("trace", 13),
                Map.entry("document_ids", 14)));
        assertFields("GenerateAnswerEvent", Map.of("content", 1, "completed", 2));
        var event = OpenVieAiProto.getDescriptor().findMessageTypeByName("GenerateAnswerEvent");
        assertEquals(List.of("payload"), event.getOneofs().stream()
                .map(Descriptors.OneofDescriptor::getName).toList());
        assertFields("GenerateAnswerResponse", Map.ofEntries(
                Map.entry("generation_id", 1), Map.entry("authoritative_revision", 2),
                Map.entry("answer", 3), Map.entry("citations", 4), Map.entry("input_tokens", 5),
                Map.entry("output_tokens", 6), Map.entry("cache_tier", 7),
                Map.entry("avoided_input_tokens", 8), Map.entry("avoided_output_tokens", 9),
                Map.entry("suggested_questions", 10)));
        assertNull(OpenVieAiProto.getDescriptor().findMessageTypeByName("TicketDraft"));
        assertNull(OpenVieAiProto.getDescriptor().findMessageTypeByName(
                "PrepareInterviewSessionRequest"));
        assertNull(OpenVieAiProto.getDescriptor().findEnumTypeByName("VisibilityMode"));
    }

    private void assertFields(String messageName, Map<String, Integer> expected) {
        var descriptor = OpenVieAiProto.getDescriptor().findMessageTypeByName(messageName);
        Map<String, Integer> actual = new LinkedHashMap<>();
        descriptor.getFields().forEach(field -> actual.put(field.getName(), field.getNumber()));
        assertEquals(expected, actual);
    }
}
