package com.openvie.api.document.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.openvie.api.document.enums.DocumentStatus;
import com.openvie.api.document.enums.DocumentType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One row of a knowledge base's document list. `jobId` links the document to its "
        + "ingestion job; `chunkCount` is populated once indexing has finished.")
public record DocumentListItemResponse(
        UUID id,
        UUID jobId,
        String fileName,
        DocumentType fileType,
        long fileSizeBytes,
        UUID knowledgeBaseId,
        @Schema(description = "Ingestion state: PENDING, PROCESSING, COMPLETED, or FAILED.") DocumentStatus status,
        Integer chunkCount,
        @Schema(description = "Set when ingestion FAILED; explains the worker-side reason.") String errorMessage,
        LocalDateTime uploadedAt,
        UUID uploadedBy
) {
}
