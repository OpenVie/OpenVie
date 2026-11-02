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

package com.openvie.api.bizapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * The BizApp manifest ("bizapp.v1"), the declarative contract that defines a
 * Tier-3 business app: entities, executable blocks bound to existing platform
 * services, org RBAC, and the render order of runtime views.
 *
 * <p>Rich shapes (block params, report SQL) stay as raw maps: the manifest is
 * data, and {@code BizAppManifestValidator} — schema-wise the same trick
 * Flowise plays with {@code INodeParams[]} — is the only authority that makes
 * a manifest runnable.
 */
public final class BizAppManifest {

    public static final String SCHEMA = "bizapp.v1";

    private BizAppManifest() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Localized(String vi, String en) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(
            String name,
            String type,
            Boolean required,
            Integer maxLen,
            String format,
            List<String> options) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entity(
            String name,
            Localized label,
            List<Field> fields,
            Map<String, List<String>> hooks) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Block(String id, String type, Map<String, Object> params) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ui(List<String> views) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Manifest(
            String schema,
            String kind,
            Localized name,
            Integer version,
            String icon,
            Map<String, List<String>> rbac,
            List<Entity> entities,
            List<Block> blocks,
            Ui ui) {

        public String entityField(String entityName, String fieldName) {
            if (entities == null) {
                return null;
            }
            return entities.stream()
                    .filter(entity -> entityName.equals(entity.name()))
                    .flatMap(entity -> entity.fields() == null
                            ? java.util.stream.Stream.empty()
                            : entity.fields().stream())
                    .filter(field -> fieldName.equals(field.name()))
                    .map(Field::type)
                    .findFirst()
                    .orElse(null);
        }

        public java.util.Optional<Block> block(String blockId) {
            if (blocks == null) {
                return java.util.Optional.empty();
            }
            return blocks.stream().filter(block -> blockId.equals(block.id())).findFirst();
        }
    }
}
