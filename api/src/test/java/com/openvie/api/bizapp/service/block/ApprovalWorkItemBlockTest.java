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

import com.openvie.api.bizapp.service.block.BizAppBlockExecutor.BlockContext;
import com.openvie.api.bizapp.service.block.BizAppBlockExecutor.BlockResult;
import com.openvie.api.bizapp.service.block.BizAppBlockExecutor.BlockSpec;
import com.openvie.api.workflow.api.WorkflowRuntimeApi;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalWorkItemBlockTest {

    @Test
    void startsProcessWithWorkspaceIdForTheEngineInbox() {
        WorkflowRuntimeApi runtime = mock(WorkflowRuntimeApi.class);
        when(runtime.start(any(), any(), anyString(), anyString(), anyMap()))
                .thenReturn(new WorkflowRuntimeApi.StartedProcess("process-1", "definition-1"));
        ApprovalWorkItemBlock block = new ApprovalWorkItemBlock(runtime);

        UUID orgId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        BlockContext context = new BlockContext(
                orgId, tenantId, actorId, "inventory", "shopping_requests", recordId,
                Map.of(), "definition-1");

        BlockResult result = block.execute(
                new BlockSpec("approval", Map.of("candidateRole", "WORKSPACE_ADMIN")), context);

        assertEquals("process-1", result.summary());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(runtime).start(eq(tenantId), eq(actorId), eq("definition-1"),
                eq(recordId.toString()), variables.capture());
        assertEquals(tenantId.toString(), variables.getValue().get("workspaceId"));
        assertEquals("inventory", variables.getValue().get("appKind"));
        assertEquals("shopping_requests", variables.getValue().get("entity"));
    }
}
