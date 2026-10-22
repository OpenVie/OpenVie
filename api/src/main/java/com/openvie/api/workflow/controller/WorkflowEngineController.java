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

package com.openvie.api.workflow.controller;

import com.openvie.api.common.controller.BaseController;
import com.openvie.api.workflow.dto.EngineDtos;
import com.openvie.api.workflow.dto.EngineDtos.EngineClaimRequest;
import com.openvie.api.workflow.dto.EngineDtos.EngineCompleteRequest;
import com.openvie.api.workflow.dto.EngineDtos.EngineDefinitionView;
import com.openvie.api.workflow.dto.EngineDtos.EngineProcessHistoryView;
import com.openvie.api.workflow.dto.EngineDtos.EngineProcessView;
import com.openvie.api.workflow.dto.EngineDtos.EngineTasks;
import com.openvie.api.workflow.dto.EngineDtos.StartEngineProcessRequest;
import com.openvie.api.workflow.service.WorkflowEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generic BPMN engine surface for integrators (L2): workspace-scoped by default
 * so tenant isolation is the engine's own tenancy.
 *
 * <p>The document-approval flow is one application, not the engine itself;
 * its tasks stay with the approval endpoints.
 */
@Tag(name = "Workflow engine",
        description = "Generic BPMN surface: deploy definitions, start processes with variables, generic task inbox, history")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('WORKSPACE_ADMIN','MEMBER','ORG_OWNER')")
@SecurityRequirement(name = "bearerAuth")
public class WorkflowEngineController extends BaseController {

    private final WorkflowEngineService engineService;
    @Operation(summary = "Deploy a BPMN definition (admin)",
            description = "Accepts any BPMN 2.0 XML; the process `id` inside the XML is the definition key used to "
                    + "start processes. Deployments are scoped to the active workspace.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deployment stored", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Not a BPMN XML document",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Deployers need the workspace-admin role"),
            @ApiResponse(responseCode = "409", description = "Malformed process definition refused by the engine",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PostMapping("/api/v1/workflow/engine/definitions")
    @PreAuthorize("hasRole('WORKSPACE_ADMIN') or hasRole('ORG_OWNER')")
    public EngineDefinitionView deploy(
            @Valid @RequestBody EngineDtos.DeployDefinitionRequest body,
            HttpServletRequest request) {
        return engineService.deployDefinition(
                getTenantId(request), getUserId(request), body.name(), body.bpmnXml());
    }

    @Operation(summary = "List the workspace's process definitions")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Definitions", useReturnTypeSchema = true)})
    @GetMapping("/api/v1/workflow/engine/definitions")
    public List<EngineDefinitionView> definitions(HttpServletRequest request) {
        return engineService.listDefinitions(getTenantId(request));
    }

    @Operation(summary = "Start a process with variables",
            description = "Starts the latest version of the definition in the active workspace; the returned id is "
                    + "`processId` used by the history endpoint.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Process started", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "Definition not found in this workspace",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PostMapping("/api/v1/workflow/engine/processes")
    public EngineProcessView start(
            @Valid @RequestBody StartEngineProcessRequest body,
            HttpServletRequest request) {
        return engineService.startProcess(getTenantId(request), getUserId(request), body);
    }

    @Operation(summary = "List running processes",
            description = "Active process instances of the workspace, optionally narrowed by `definitionKey`. "
                    + "`latest_version_only` pins one version's id instead of the key.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Processes", useReturnTypeSchema = true)})
    @GetMapping("/api/v1/workflow/engine/processes")
    public List<EngineProcessView> processes(
            @RequestParam(value = "definitionKey", required = false) String definitionKey,
            @RequestParam(value = "latest_version_only", required = false, defaultValue = "true")
            boolean latestVersionOnly,
            HttpServletRequest request) {
        return engineService.listProcesses(getTenantId(request), definitionKey, latestVersionOnly);
    }

    @Operation(summary = "History of one process",
            description = "Terminal state, durations, and variables of a process (running or ended); history survives "
                    + "engine restarts.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "History", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "Process not found in this workspace",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @GetMapping("/api/v1/workflow/engine/history/processes/{processId}")
    public EngineProcessHistoryView history(@PathVariable UUID processId, HttpServletRequest request) {
        return engineService.processHistory(getTenantId(request), processId);
    }

    @Operation(summary = "Generic task inbox",
            description = "Unassigned or claimed tasks across every definition of the workspace. Approval-flow tasks "
                    + "are listed with `approvalDomain: true` and refused for raw claim/complete.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Tasks", useReturnTypeSchema = true)})
    @GetMapping("/api/v1/workflow/engine/tasks")
    public EngineTasks tasks(
            @RequestParam(value = "definitionKey", required = false) String definitionKey,
            @RequestParam(value = "unassigned", required = false, defaultValue = "false") boolean unassignedOnly,
            @RequestParam(value = "assignee", required = false) UUID assigneeId,
            @RequestParam(value = "limit", required = false, defaultValue = "25") int limit,
            HttpServletRequest request) {
        return engineService.listTasks(getTenantId(request), definitionKey, unassignedOnly, assigneeId, limit);
    }

    @Operation(summary = "Claim a task",
            description = "Defaults to assigning the caller; explicitly claim for a service member by passing its "
                    + "`assignee` user id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task claimed", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "403", description = "Task outside the active workspace"),
            @ApiResponse(responseCode = "404", description = "Task not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Already claimed, or the task belongs to the approval journal",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PostMapping("/api/v1/workflow/engine/tasks/{taskId}/claim")
    public Map<String, Object> claim(
            @PathVariable UUID taskId,
            @RequestBody(required = false) EngineClaimRequest body,
            HttpServletRequest request) {
        return engineService.claimTask(getTenantId(request), getUserId(request), taskId, body);
    }

    @Operation(summary = "Complete a task with variables",
            description = "Writes the provided variables and moves the process forward per its BPMN flow; the response "
                    + "reports whether the instance reached its end.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task completed", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "403", description = "Task outside the active workspace"),
            @ApiResponse(responseCode = "404", description = "Task not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Task belongs to the approval journal",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PostMapping("/api/v1/workflow/engine/tasks/{taskId}/complete")
    public Map<String, Object> complete(
            @PathVariable UUID taskId,
            @RequestBody(required = false) EngineCompleteRequest body,
            HttpServletRequest request) {
        return engineService.completeTask(getTenantId(request), getUserId(request), taskId, body);
    }
}
