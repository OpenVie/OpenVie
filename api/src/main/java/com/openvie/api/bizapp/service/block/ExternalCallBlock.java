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

import com.openvie.api.bizapp.connector.BizAppConnectorSpecParser;
import com.openvie.api.bizapp.service.BizAppConnectorJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lifecycle hook for {@code external_call}: validate the connector declaration
 * and enqueue a durable job inside the record transaction.
 *
 * <p>Provider I/O never runs here. A manifest that cannot enqueue rolls the
 * record write back; a provider failure is retried by the worker and surfaced
 * in the job/audit trail instead of failing the user's write.
 */
@Component
@RequiredArgsConstructor
public class ExternalCallBlock implements BizAppBlockExecutor {

    public static final String TYPE = "external_call";

    private final BizAppConnectorSpecParser specParser;
    private final BizAppConnectorJobService jobService;

    @Override
    public String supports() {
        return TYPE;
    }

    @Override
    public BlockResult execute(BlockSpec spec, BlockContext context) {
        specParser.parse(new com.openvie.api.bizapp.dto.BizAppManifest.Block(
                spec.blockId(), TYPE, spec.params()));
        var jobId = jobService.enqueue(
                context.tenantId(),
                context.orgId(),
                context.appKind(),
                context.entity(),
                context.recordId(),
                spec.blockId(),
                context.actorId(),
                "HOOK");
        return new BlockResult("queued connector job " + jobId);
    }
}
