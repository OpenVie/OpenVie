package com.cacanode.api.document.dto;

import java.util.UUID;

import com.cacanode.api.document.enums.DocumentStatus;

public record DocumentUploadResponse(
        UUID id,
        UUID jobId,
        String fileName,
        DocumentStatus status
) {
}
