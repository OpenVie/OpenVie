package com.cacanode.api.document.cache;

import com.cacanode.api.document.dto.DocumentListItemResponse;

import java.util.List;

public record DocumentListCacheValue(List<DocumentListItemResponse> documents, long totalCount) {
    public DocumentListCacheValue {
        documents = List.copyOf(documents);
    }

    public DocumentListCacheValue(List<DocumentListItemResponse> documents) {
        this(documents, documents.size());
    }
}
