package com.cacanode.api.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.seaweedfs")
public record SeaweedFsProperties(
        String s3Endpoint,
        String bucket,
        String accessKey,
        String secretKey,
        String region
) {
    public String resolvedRegion() {
        return region == null || region.isBlank() ? "us-east-1" : region;
    }
}
