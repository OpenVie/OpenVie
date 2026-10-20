package com.openvie.api.document.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.openvie.api.document.enums.DocumentStatus;
import com.openvie.api.document.enums.DocumentType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Ingestion status of one document — poll while a fresh upload is still being indexed. "
        + "Selectable as chat scope only in COMPLETED state.")
public record DocumentStatusResponse(
        UUID id,
        UUID jobId,
        String fileName,
        DocumentType fileType,
        Long fileSizeBytes,
        LocalDateTime uploadedAt,
        UUID knowledgeBaseId,
        @Schema(description = "Ingestion state: PENDING, PROCESSING, COMPLETED, or FAILED.") DocumentStatus status,
        Integer chunkCount,
        @Schema(description = "Set when ingestion FAILED; explains the worker-side reason.") String errorMessage,
        UUID uploadedBy
) {
}
