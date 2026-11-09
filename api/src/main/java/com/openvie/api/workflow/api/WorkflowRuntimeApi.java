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

package com.openvie.api.workflow.api;

import java.util.Map;
import java.util.UUID;

/**
 * Published boundary: process-engine actions other modules may drive.
 *
 * <p>The BizApp module deploys its approval definitions, starts processes, and
 * moves in-flight approval tasks when a manifest changes its approver role
 * through this contract instead of the engine service internals. Task claim /
 * complete stay out on purpose — those remain journaled decisions behind
 * {@code /api/v1/workflow/engine/tasks/**}.
 */
public interface WorkflowRuntimeApi {

    DeployedDefinition deploy(UUID workspaceId, UUID deployerId, String name, String bpmnXml);

    StartedProcess start(UUID workspaceId, UUID initiatorId, String definitionKey,
            String businessKey, Map<String, Object> variables);

    /**
     * Move unclaimed approval tasks of one app to the role selected by a new
     * manifest, so an approval-role change applies to work already in flight.
     */
    void reassignPendingTasks(UUID workspaceId, String appKind, String oldGroup, String newGroup);

    record DeployedDefinition(String deploymentId, String key, String name, int version) {}

    record StartedProcess(String processId, String definitionKey) {}
}
