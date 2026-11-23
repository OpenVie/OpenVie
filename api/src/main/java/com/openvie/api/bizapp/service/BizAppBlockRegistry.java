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

package com.openvie.api.bizapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical block catalog shipped in {@code contracts/bizapp/blocks.v1.json}.
 *
 * <p>The runtime and composer read the same catalog; adding a block type starts
 * here instead of in scattered Java/TypeScript constants.
 */
public final class BizAppBlockRegistry {

    private static final String RESOURCE = "contracts/bizapp/blocks.v1.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BizAppBlockRegistry DEFAULT = load();

    private final List<BlockDefinition> blocks;
    private final Map<String, BlockDefinition> byType;
    private final Set<String> types;
    private final Set<String> hookableTypes;

    private BizAppBlockRegistry(List<BlockDefinition> blocks) {
        this.blocks = List.copyOf(blocks);
        this.byType = new LinkedHashMap<>();
        this.types = new LinkedHashSet<>();
        this.hookableTypes = new LinkedHashSet<>();
        for (BlockDefinition block : blocks) {
            if (byType.put(block.type(), block) != null) {
                throw new IllegalStateException("duplicate block type in registry: " + block.type());
            }
            types.add(block.type());
            if (block.hookable()) {
                hookableTypes.add(block.type());
            }
        }
    }

    public static BizAppBlockRegistry defaultRegistry() {
        return DEFAULT;
    }

    public List<BlockDefinition> blocks() {
        return blocks;
    }

    public BlockDefinition get(String type) {
        return byType.get(type);
    }

    public Set<String> types() {
        return Set.copyOf(types);
    }

    public Set<String> hookableTypes() {
        return Set.copyOf(hookableTypes);
    }

    public List<BlockDefinition> paletteBlocks() {
        return blocks.stream().filter(BlockDefinition::palette).toList();
    }

    private static BizAppBlockRegistry load() {
        try (InputStream stream = BizAppBlockRegistry.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing block registry resource " + RESOURCE);
            }
            JsonNode root = MAPPER.readTree(stream);
            JsonNode blocks = root.get("blocks");
            if (blocks == null || !blocks.isArray()) {
                throw new IllegalStateException("block registry has no blocks array");
            }
            List<BlockDefinition> parsed = MAPPER.convertValue(
                    blocks, new TypeReference<List<BlockDefinition>>() {});
            return new BizAppBlockRegistry(parsed);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("cannot load block registry " + RESOURCE, ex);
        }
    }

    public record BlockDefinition(
            String type,
            boolean hookable,
            boolean palette,
            Map<String, Object> defaults,
            String paramSchema
    ) {
        public BlockDefinition {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("block registry entry needs a type");
            }
            defaults = defaults == null ? Map.of() : Map.copyOf(defaults);
        }
    }
}
