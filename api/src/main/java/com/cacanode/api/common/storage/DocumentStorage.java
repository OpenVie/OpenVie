package com.cacanode.api.common.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface DocumentStorage {
    void store(String key, MultipartFile file);

    void store(String key, byte[] content, String contentType);

    void store(String key, InputStream content, long contentLength, String contentType);

    StoredDocument load(String key);

    StoredDocument loadRange(String key,long startInclusive,long endInclusive);

    StoredObjectMetadata metadata(String key);

    boolean exists(String key);

    void delete(String key);
}
