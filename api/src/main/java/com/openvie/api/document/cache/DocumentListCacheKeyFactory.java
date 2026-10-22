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

package com.openvie.api.document.cache;

import com.openvie.api.common.cache.CacheKeyFactory;
import com.openvie.api.document.enums.DocumentStatus;
import com.openvie.api.document.enums.DocumentType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.time.LocalDate;

@Component
public class DocumentListCacheKeyFactory {
    private final CacheKeyFactory keyFactory;

    public DocumentListCacheKeyFactory(CacheKeyFactory keyFactory) {
        this.keyFactory = keyFactory;
    }

    public CanonicalFilters legacy() {
        return canonical("legacy", 0, 0, null, null, null, null, null, null, null);
    }

    public CanonicalFilters paged(
            int page,
            int size,
            String searchText,
            DocumentStatus status,
            DocumentType type
    ) {
        return canonical("paged", page, size, searchText, status, type,
                null, null, "uploaded", "desc");
    }

    public CanonicalFilters paged(
            int page, int size, String searchText, DocumentStatus status, DocumentType type,
            LocalDate uploadedFrom, LocalDate uploadedTo,
            String sort, String direction
    ) {
        return canonical("paged", page, size, searchText, status, type,
                uploadedFrom, uploadedTo, sort, direction);
    }

    public String key(UUID tenantId, UUID knowledgeBaseId, long generation, CanonicalFilters filters) {
        return keyFactory.build("documents", "tenant", tenantId.toString(), "kb", knowledgeBaseId.toString(),
                "gen", Long.toString(generation), "filters", filters.sha256());
    }

    private CanonicalFilters canonical(
            String mode,
            int page,
            int size,
            String searchText,
            DocumentStatus status,
            DocumentType type,

            LocalDate uploadedFrom,
            LocalDate uploadedTo,
            String sort,
            String direction
    ) {
        String normalizedSearch = searchText == null ? null : Normalizer
                .normalize(searchText.strip(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
        if (normalizedSearch != null && normalizedSearch.isBlank()) normalizedSearch = null;
        String canonical = "mode=" + mode + "\npage=" + page + "\nsize=" + size
                + "\nsearch=" + (normalizedSearch == null ? "" : normalizedSearch)
                + "\nstatus=" + (status == null ? "" : status.name())
                + "\ntype=" + (type == null ? "" : type.name())
                + "\nfrom=" + (uploadedFrom == null ? "" : uploadedFrom)
                + "\nto=" + (uploadedTo == null ? "" : uploadedTo)
                + "\nsort=" + (sort == null ? "" : sort)
                + "\ndirection=" + (direction == null ? "" : direction);
        return new CanonicalFilters(mode, page, size, normalizedSearch, status, type,
                uploadedFrom, uploadedTo, sort, direction, sha256(canonical));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record CanonicalFilters(
            String mode,
            int page,
            int size,
            String searchText,
            DocumentStatus status,
            DocumentType type,

            LocalDate uploadedFrom,
            LocalDate uploadedTo,
            String sort,
            String direction,
            String sha256
    ) {}
}
