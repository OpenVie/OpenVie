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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BizAppBlockRegistryTest {

    private final BizAppBlockRegistry registry = BizAppBlockRegistry.defaultRegistry();

    @Test
    void exposesTheCanonicalBlockCatalog() {
        assertEquals(7, registry.types().size());
        assertEquals(7, registry.paletteBlocks().size());
        assertTrue(registry.types().contains("knowledge_base"));
        assertFalse(registry.types().contains("unknown_block"));
    }

    @Test
    void onlyExecutionBlocksAreHookable() {
        assertEquals(4, registry.hookableTypes().size());
        assertTrue(registry.hookableTypes().contains("approval_workitem"));
        assertTrue(registry.hookableTypes().contains("external_call"));
        assertFalse(registry.hookableTypes().contains("report"));
        assertFalse(registry.hookableTypes().contains("assistant"));
    }

    @Test
    void keepsSafeDefaultsForNewBlocks() {
        assertEquals("WORKSPACE_ADMIN",
                registry.get("approval_workitem").defaults().get("candidateRole"));
        assertEquals(2, ((java.util.List<?>) registry.get("webhook_out")
                .defaults().get("events")).size());
    }
}
