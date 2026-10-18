package com.openvie.api.document.dto;

import java.util.UUID;

import com.openvie.api.document.enums.DocumentStatus;

public record DocumentUploadResponse(
        UUID id,
        UUID jobId,
        String fileName,
        DocumentStatus status
) {
}
