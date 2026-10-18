package com.openvie.api.document.dto;

public record DocumentDownloadResponse(
        String fileName,
        String contentType,
        byte[] content
) {
}
