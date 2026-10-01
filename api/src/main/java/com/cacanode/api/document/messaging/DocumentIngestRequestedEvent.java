package com.cacanode.api.document.messaging;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DocumentIngestRequestedEvent(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("job_id") UUID jobId,
        @JsonProperty("tenant_id") UUID tenantId,
        @JsonProperty("knowledge_base_id") UUID knowledgeBaseId,
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("uploader_id") UUID uploaderId,
        @JsonProperty("storage_key") String storageKey,
        @JsonProperty("file_name") String fileName,
        @JsonProperty("content_type") String contentType,
        @JsonProperty("file_size_bytes") long fileSizeBytes,
        @JsonProperty("occurred_at") Instant occurredAt
) {
}
