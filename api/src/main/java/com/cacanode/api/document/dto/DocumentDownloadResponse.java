package com.cacanode.api.document.dto;

public record DocumentDownloadResponse(
        String fileName,
        String contentType,
        byte[] content
) {
}
