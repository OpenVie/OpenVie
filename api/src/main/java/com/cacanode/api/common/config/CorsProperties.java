package com.cacanode.api.common.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * CORS settings per environment. Use {@code allowed-origin-patterns} for local dev
 * (e.g. {@code http://localhost:*}) so any port and 127.0.0.1 work with credentials.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    private List<String> allowedOrigins = new ArrayList<>();
    private List<String> allowedOriginPatterns = new ArrayList<>();
}
