package com.openvie.api.common.storage;

public record StoredObjectMetadata(long contentLength,String contentType,String eTag) {}
