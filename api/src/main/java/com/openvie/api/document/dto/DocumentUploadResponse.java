package com.openvie.api.document.dto;

import java.util.UUID;

import com.openvie.api.document.enums.DocumentStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Upload acknowledgement: indexing runs asynchronously, so status starts at PENDING/PROCESSING.")
public record DocumentUploadResponse(
        UUID id,
        UUID jobId,
        String fileName,
        DocumentStatus status
) {
}
