package com.cacanode.api.common.storage;

public record StoredObjectMetadata(long contentLength,String contentType,String eTag) {}
