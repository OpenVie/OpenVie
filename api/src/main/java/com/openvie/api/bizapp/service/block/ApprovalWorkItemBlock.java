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

import com.openvie.api.workflow.api.WorkflowRuntimeApi;
import com.openvie.api.common.exception.custom.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Starts the app's Flowable process for one submitted record; the process key
 * rides on the context (installed + deployed at install time). Task
 * claim/complete stay on the generic engine endpoints, and the decision is
 * written back by {@code BizAppRecordDecisionDelegate} at the end of the
 * definition.
 */
@Component
@RequiredArgsConstructor
public class ApprovalWorkItemBlock implements BizAppBlockExecutor {

    public static final String TYPE = "approval_workitem";

    private final WorkflowRuntimeApi workflowRuntimeApi;

    @Override
    public String supports() {
        return TYPE;
    }

    @Override
    public BlockResult execute(BlockSpec spec, BlockContext context) {
        if (context.processKey() == null) {
            throw new ConflictException("This app's approval definition is not deployed");
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        if (context.recordId() != null) {
            variables.put("recordId", context.recordId().toString());
        }
        variables.put("entity", context.entity());
        variables.put("appKind", context.appKind());
        variables.put("candidateRole", string(spec.params(), "candidateRole", "WORKSPACE_ADMIN"));
        WorkflowRuntimeApi.StartedProcess started = workflowRuntimeApi.start(
                context.tenantId(), context.actorId(), context.processKey(),
                context.recordId() == null ? null : context.recordId().toString(), variables);
        return new BlockResult(started.processId());
    }

    private String string(Map<String, Object> params, String key, String fallback) {
        Object value = params == null ? null : params.get(key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }
}
