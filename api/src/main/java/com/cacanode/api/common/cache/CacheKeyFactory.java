package com.cacanode.api.common.cache;

import com.cacanode.api.common.config.CacheProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class CacheKeyFactory {

    private static final Pattern TRUSTED_PART = Pattern.compile("[A-Za-z0-9._-]+");
    private final String prefix;

    @Autowired
    public CacheKeyFactory(CacheProperties properties) {
        this(properties.getKeyPrefix());
    }

    public CacheKeyFactory(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("Cache key prefix must not be blank");
        }
        this.prefix = prefix.replaceAll(":+$", "");
    }

    public String build(String domain, String... trustedSegments) {
        List<String> parts = new ArrayList<>();
        parts.add(prefix);
        parts.add(validate(domain));
        for (String segment : trustedSegments) {
            parts.add(validate(segment));
        }
        return String.join(":", parts);
    }

    private String validate(String part) {
        if (part == null || !TRUSTED_PART.matcher(part).matches()) {
            throw new IllegalArgumentException("Cache key parts must be trusted opaque segments");
        }
        return part;
    }
}
