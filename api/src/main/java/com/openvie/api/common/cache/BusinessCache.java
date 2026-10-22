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

import java.time.Duration;

public enum BusinessCache {
    WORKSPACE("workspace") {
        public boolean domainEnabled(CacheProperties p) { return p.isWorkspaceEnabled(); }
        public int ttlSeconds(CacheProperties p) { return p.getWorkspaceTtlSeconds(); }
    },
    USER_DIRECTORY("user-directory") {
        public boolean domainEnabled(CacheProperties p) { return p.isUserDirectoryEnabled(); }
        public int ttlSeconds(CacheProperties p) { return p.getUserDirectoryTtlSeconds(); }
    },
    DOCUMENT_LIST("document-list") {
        public boolean domainEnabled(CacheProperties p) { return p.isDocumentListEnabled(); }
        public int ttlSeconds(CacheProperties p) { return p.getDocumentListTtlSeconds(); }
    };

    private final String label;

    BusinessCache(String label) {
        this.label = label;
    }

    public String label() { return label; }
    public abstract boolean domainEnabled(CacheProperties properties);
    public abstract int ttlSeconds(CacheProperties properties);

    public boolean enabled(CacheProperties properties) {
        return properties.isEnabled() && properties.isBusinessReadEnabled() && domainEnabled(properties);
    }

    public Duration ttl(CacheProperties properties) {
        return Duration.ofSeconds(Math.max(1, ttlSeconds(properties)));
    }
}
