package com.cacanode.api.ai;

import com.cacanode.ai.v1.CacanodeAiProto;
import com.google.protobuf.Descriptors;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProtobufContractTest {
    @Test
    void inferenceServiceExposesOnlyTheThreeRagMethods() {
        var service = CacanodeAiProto.getDescriptor().findServiceByName("InferenceService");
        assertEquals(List.of(
                "GenerateAnswer",
                "ListDocumentUnits",
                "DeleteDocumentIndex"),
                service.getMethods().stream().map(Descriptors.MethodDescriptor::getName).toList());
    }

    @Test
    void generateAnswerFieldsAreFrozen() {
        assertFields("GenerateAnswerRequest", Map.ofEntries(
                Map.entry("generation_id", 1), Map.entry("turn_id", 2),
                Map.entry("tenant_id", 3), Map.entry("chatbot_id", 4),
                Map.entry("knowledge_base_id", 5), Map.entry("authoritative_revision", 6),
                Map.entry("channel", 7), Map.entry("locale", 8), Map.entry("question", 9),
                Map.entry("prior_messages", 10), Map.entry("tenant_name", 11),
                Map.entry("prompt_schema_version", 12), Map.entry("trace", 13)));
        assertFields("GenerateAnswerResponse", Map.ofEntries(
                Map.entry("generation_id", 1), Map.entry("authoritative_revision", 2),
                Map.entry("answer", 3), Map.entry("citations", 4), Map.entry("input_tokens", 5),
                Map.entry("output_tokens", 6), Map.entry("cache_tier", 7),
                Map.entry("avoided_input_tokens", 8), Map.entry("avoided_output_tokens", 9)));
        assertNull(CacanodeAiProto.getDescriptor().findMessageTypeByName("TicketDraft"));
        assertNull(CacanodeAiProto.getDescriptor().findMessageTypeByName(
                "PrepareInterviewSessionRequest"));
        assertNull(CacanodeAiProto.getDescriptor().findEnumTypeByName("VisibilityMode"));
    }

    private void assertFields(String messageName, Map<String, Integer> expected) {
        var descriptor = CacanodeAiProto.getDescriptor().findMessageTypeByName(messageName);
        Map<String, Integer> actual = new LinkedHashMap<>();
        descriptor.getFields().forEach(field -> actual.put(field.getName(), field.getNumber()));
        assertEquals(expected, actual);
    }
}
