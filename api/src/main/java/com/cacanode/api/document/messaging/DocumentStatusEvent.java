package com.cacanode.api.document.messaging;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DocumentStatusEvent(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("job_id") UUID jobId,
        @JsonProperty("tenant_id") UUID tenantId,
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("status") String status,
        @JsonProperty("chunk_count") Integer chunkCount,
        @JsonProperty("error_message") String errorMessage
) {
}
