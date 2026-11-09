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

package com.openvie.api.bizapp.service.block;

import java.util.Map;
import java.util.UUID;

/**
 * One executable BizApp block type. Executors are the Flowise "node class"
 * analog: declarative purpose, server-side execution, org-scoped inputs.
 * Hook targets (entity {@code hooks}) resolve through {@code BizAppBlockRegistry}.
 */
public interface BizAppBlockExecutor {

    /** Manifest block type this executor handles. */
    String supports();

    /**
     * @return a human-readable result stored in the hook audit trail; failures
     *         throw and are caught per-target so one failing hook never rolls
     *         back the record write.
     */
    BlockResult execute(BlockSpec spec, BlockContext context);

    /** Manifest parameters of the block instance being executed. */
    record BlockSpec(String blockId, Map<String, Object> params) {}

    /** Who and where: org, workspace, acting user; plus the app's deployed process key. */
    record BlockContext(
            UUID orgId,
            UUID tenantId,
            UUID actorId,
            String appKind,
            String entity,
            UUID recordId,
            Map<String, Object> payload,
            String processKey,
            Map<String, Object> config,
            Integer version,
            UUID recordCreatedBy,
            Map<String, String> fieldLabels) {
        public BlockContext {
            fieldLabels = fieldLabels == null ? Map.of() : Map.copyOf(fieldLabels);
        }

        public BlockContext(
                UUID orgId,
                UUID tenantId,
                UUID actorId,
                String appKind,
                String entity,
                UUID recordId,
                Map<String, Object> payload,
                String processKey,
                Map<String, Object> config,
                Integer version) {
            this(orgId, tenantId, actorId, appKind, entity, recordId, payload, processKey,
                    config, version, actorId, Map.of());
        }

        public BlockContext(
                UUID orgId,
                UUID tenantId,
                UUID actorId,
                String appKind,
                String entity,
                UUID recordId,
                Map<String, Object> payload,
                String processKey) {
            this(orgId, tenantId, actorId, appKind, entity, recordId, payload, processKey,
                    Map.of(), 1, actorId, Map.of());
        }
    }

    record BlockResult(String summary, Map<String, Object> runtimeUpdates) {
        public BlockResult(String summary) {
            this(summary, Map.of());
        }
    }
}
