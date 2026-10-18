package com.openvie.api.common.storage;

public record StoredDocument(
        byte[] content,
        String contentType
) {
}
