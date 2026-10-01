package com.cacanode.api.common.storage;

public record StoredDocument(
        byte[] content,
        String contentType
) {
}
