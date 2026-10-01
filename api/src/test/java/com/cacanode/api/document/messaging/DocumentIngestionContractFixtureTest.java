package com.cacanode.api.document.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class DocumentIngestionContractFixtureTest {

    private static final Path CONTRACT = Path.of(
            "..", "contracts", "document-ingestion", "v1");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void javaRecordsDeserializeCanonicalCrossLanguageFixtures() throws Exception {
        var request = mapper.readValue(
                Files.readString(CONTRACT.resolve("fixtures/request.json")),
                DocumentIngestRequestedEvent.class);
        assertEquals("1.0", request.schemaVersion());
        assertNotNull(request.eventId());
        assertNotNull(request.occurredAt());

        for (String fixture : Set.of(
                "status-processing.json", "status-completed.json", "status-failed.json")) {
            var event = mapper.readValue(
                    Files.readString(CONTRACT.resolve("fixtures").resolve(fixture)),
                    DocumentStatusEvent.class);
            assertEquals("1.0", event.schemaVersion());
            assertNotNull(event.eventId());
            assertNotNull(event.status());
        }
    }

    @Test
    void fixturesContainExactlyTheSchemaRequiredFields() throws Exception {
        assertRequiredFields("request.schema.json", "fixtures/request.json");
        assertRequiredFields("status.schema.json", "fixtures/status-processing.json");
        assertRequiredFields("status.schema.json", "fixtures/status-completed.json");
        assertRequiredFields("status.schema.json", "fixtures/status-failed.json");
    }

    private void assertRequiredFields(String schemaName, String fixtureName) throws Exception {
        JsonNode schema = mapper.readTree(Files.readString(CONTRACT.resolve(schemaName)));
        JsonNode fixture = mapper.readTree(Files.readString(CONTRACT.resolve(fixtureName)));
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(value -> required.add(value.asText()));
        Set<String> actual = new HashSet<>();
        fixture.fieldNames().forEachRemaining(actual::add);
        assertEquals(required, actual);
    }
}
