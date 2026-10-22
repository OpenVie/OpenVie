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

package com.openvie.api.workflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Wire records of the generic workflow-engine surface (`/api/v1/workflow/"
 * + "engine/**`): definition deployment, process start with variables, generic
 * task inbox / claim / complete, and history. The document-approval flow is
 * one application of this engine, not the engine itself.
 */
public final class EngineDtos {
    private EngineDtos() {
    }

    @Schema(description = "Deploy one BPMN 2.0 XML definition.")
    public record DeployDefinitionRequest(
            @Schema(description = "Display name; also becomes the deployment resource name.")
            @NotBlank @Size(max = 120) String name,
            @Schema(description = "BPMN 2.0 XML. The process `id` inside the XML is the definition key used to start processes.")
            @NotBlank @Size(max = 512 * 1024) String bpmnXml
    ) {
    }

    @Schema(description = "One process definition of the workspace's engine.")
    public record EngineDefinitionView(
            String id,
            String key,
            String name,
            int version,
            LocalDateTime deployedAt,
            @Schema(description = "True when this definition was deployed through the generic engine API.")
            boolean managed
    ) {
    }

    @Schema(description = "Start a process with typed variables.")
    public record StartEngineProcessRequest(
            @Schema(description = "Definition key (the BPMN process id), or the definition id.")
            @NotBlank String definitionKey,
            @Schema(description = "Optional caller-side business key (for example an external record id).")
            String businessKey,
            @Schema(description = "Variables the definition's script/delegation logic consumes.")
            Map<String, Object> variables
    ) {
    }

    @Schema(description = "One running process instance.")
    public record EngineProcessView(
            String id,
            @Schema(description = "Definition key the instance runs.") String definitionKey,
            String definitionName,
            String businessKey,
            LocalDateTime startedAt,
            Map<String, Object> variables
    ) {
    }

    @Schema(description = "The task inbox of one workspace across every definition (including the approval flow, exposed read-only).")
    public record EngineTaskView(
            String id,
            String name,
            @Schema(description = "User id the task is claimed for, or null when unassigned.") String assignee,
            String processInstanceId,
            String definitionKey,
            @Schema(description = "Approval-flow tasks are journaled by the approval endpoints and refused here.")
            boolean approvalDomain,
            LocalDateTime createdAt,
            LocalDateTime dueDate
    ) {
    }

    @Schema(description = "Task inbox page.")
    public record EngineTasks(
            List<EngineTaskView> tasks,
            long total
    ) {
    }

    @Schema(description = "Claim a task for the caller.")
    public record EngineClaimRequest(
            @Schema(description = "Optional explicit assignee user id; defaults to the caller.")
            String assignee
    ) {
    }

    @Schema(description = "Complete a task with variables.")
    public record EngineCompleteRequest(
            Map<String, Object> variables
    ) {
    }

    @Schema(description = "Terminal state of one process instance.")
    public record EngineProcessHistoryView(
            String id,
            String definitionKey,
            String businessKey,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            @Schema(description = "True when the instance reached its end (for Approval this is the decision).")
            boolean ended,
            @Schema(description = "Duration between start and end in ms; null while running.")
            Long durationMs,
            Map<String, Object> variables
    ) {
    }
}
