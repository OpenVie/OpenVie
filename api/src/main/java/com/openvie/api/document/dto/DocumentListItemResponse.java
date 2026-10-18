package com.openvie.api.document.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.openvie.api.document.enums.DocumentStatus;
import com.openvie.api.document.enums.DocumentType;

public record DocumentListItemResponse(
        UUID id,
        UUID jobId,
        String fileName,
        DocumentType fileType,
        long fileSizeBytes,
        UUID knowledgeBaseId,
        DocumentStatus status,
        Integer chunkCount,
        String errorMessage,
        LocalDateTime uploadedAt,
        UUID uploadedBy
) {
}
