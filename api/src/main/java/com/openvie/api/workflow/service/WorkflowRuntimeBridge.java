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

package com.openvie.api.workflow.service;

import com.openvie.api.workflow.api.WorkflowRuntimeApi;
import com.openvie.api.workflow.dto.EngineDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Implements the published {@link WorkflowRuntimeApi} boundary on top of the
 * engine service. Delivering separation: BizApps get the two actions they are
 * allowed to use, and the modalities stay inside the workflow module.
 */
@Service
@RequiredArgsConstructor
public class WorkflowRuntimeBridge implements WorkflowRuntimeApi {

    private final WorkflowEngineService engineService;

    @Override
    public DeployedDefinition deploy(UUID workspaceId, UUID deployerId, String name, String bpmnXml) {
        EngineDtos.EngineDefinitionView view = engineService.deployDefinition(workspaceId, deployerId, name, bpmnXml);
        return new DeployedDefinition(view.id(), view.key(), view.name(), view.version());
    }

    @Override
    public StartedProcess start(UUID workspaceId, UUID initiatorId, String definitionKey,
            String businessKey, Map<String, Object> variables) {
        EngineDtos.EngineProcessView view = engineService.startProcess(workspaceId, initiatorId,
                new EngineDtos.StartEngineProcessRequest(definitionKey, businessKey, variables));
        return new StartedProcess(view.id(), view.definitionKey());
    }

    @Override
    public void reassignPendingTasks(UUID workspaceId, String appKind, String oldGroup, String newGroup) {
        engineService.reassignPendingTasks(workspaceId, appKind, oldGroup, newGroup);
    }
}
