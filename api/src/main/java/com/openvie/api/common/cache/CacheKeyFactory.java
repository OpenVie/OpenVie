// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.common.cache;

import com.openvie.api.common.config.CacheProperties;
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
