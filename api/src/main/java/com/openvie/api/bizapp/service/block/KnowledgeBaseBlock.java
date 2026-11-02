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

import com.openvie.api.tenant.api.TenantKnowledgeApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Knowledge base block executor for BizApps (Workstream K, K-1).
 * Binds the application to a dedicated workspace knowledge base via {@link TenantKnowledgeApi}.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-BLOCK")
public class KnowledgeBaseBlock implements BizAppBlockExecutor {

    public static final String TYPE = "knowledge_base";

    private final TenantKnowledgeApi tenantKnowledgeApi;

    @Override
    public String supports() {
        return TYPE;
    }

    @Override
    public BlockResult execute(BlockSpec spec, BlockContext context) {
        boolean graphExtraction = spec.params() != null
                && Boolean.TRUE.equals(spec.params().get("graphExtraction"));
        UUID kbId = tenantKnowledgeApi.getOrCreateAppKnowledgeBase(
                context.tenantId(), context.appKind(), graphExtraction);
        return new BlockResult("knowledge_base " + spec.blockId() + " -> " + kbId);
    }
}
