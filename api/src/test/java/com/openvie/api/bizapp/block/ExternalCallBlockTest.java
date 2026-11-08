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

package com.openvie.api.bizapp.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openvie.api.bizapp.connector.BizAppConnectorSpecParser;
import com.openvie.api.bizapp.service.BizAppConnectorJobService;
import com.openvie.api.bizapp.service.block.BizAppBlockExecutor;
import com.openvie.api.bizapp.service.block.ExternalCallBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The hook no longer performs provider I/O: it validates the connector
 * declaration and enqueues a durable job inside the record transaction.
 */
class ExternalCallBlockTest {

    private BizAppConnectorJobService jobService;
    private ExternalCallBlock block;

    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jobService = mock(BizAppConnectorJobService.class);
        block = new ExternalCallBlock(new BizAppConnectorSpecParser(), jobService);
    }

    @Test
    void enqueuesAHookJobAndReportsItsId() {
        UUID jobId = UUID.randomUUID();
        when(jobService.enqueue(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(jobId);

        var spec = new BizAppBlockExecutor.BlockSpec(
                "sendToProvider",
                Map.of(
                        "url", "https://api.signing.test/v1/envelopes",
                        "credentialRef", "provider-key"));
        var context = new BizAppBlockExecutor.BlockContext(
                orgId, tenantId, actorId, "crm", "leads", recordId,
                Map.of("title", "Test Contract"), null,
                Map.of("allowedHosts", List.of("api.signing.test")), 2);

        var result = block.execute(spec, context);

        assertEquals("queued connector job " + jobId, result.summary());
        assertTrue(result.runtimeUpdates().isEmpty());
        verify(jobService).enqueue(eq(tenantId), eq(orgId), eq("crm"), eq("leads"),
                eq(recordId), eq("sendToProvider"), eq(actorId), eq("HOOK"));
    }

    @Test
    void rejectsAManifestWithoutUrlBeforeEnqueuing() {
        var spec = new BizAppBlockExecutor.BlockSpec("sendToProvider", Map.of());
        var context = new BizAppBlockExecutor.BlockContext(
                orgId, tenantId, actorId, "crm", "leads", recordId, Map.of(), null, Map.of(), 1);

        assertThrows(com.openvie.api.common.exception.custom.BadRequestException.class,
                () -> block.execute(spec, context));
    }

    @Test
    void propagatesEnqueueFailuresSoTheWriteRollsBack() {
        when(jobService.enqueue(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new com.openvie.api.common.exception.custom.BadRequestException(
                        "app crm is not installed"));
        var spec = new BizAppBlockExecutor.BlockSpec(
                "sendToProvider",
                Map.of("url", "https://api.signing.test/v1", "credentialRef", "key"));
        var context = new BizAppBlockExecutor.BlockContext(
                orgId, tenantId, actorId, "crm", "leads", recordId, Map.of(), null, Map.of(), 1);

        var error = assertThrows(com.openvie.api.common.exception.custom.BadRequestException.class,
                () -> block.execute(spec, context));
        assertTrue(error.getMessage().contains("not installed"));
    }
}
