package com.openvie.api.workflow.controller;

import com.openvie.api.common.controller.BaseController;
import com.openvie.api.common.exception.ErrorResponse;
import com.openvie.api.workflow.dto.WorkflowDtos;
import com.openvie.api.workflow.dto.WorkflowDtos.CompleteTaskRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.CompleteTaskResponse;
import com.openvie.api.workflow.dto.WorkflowDtos.ProcessDetail;
import com.openvie.api.workflow.dto.WorkflowDtos.ProcessListResult;
import com.openvie.api.workflow.dto.WorkflowDtos.ProcessLog;
import com.openvie.api.workflow.dto.WorkflowDtos.SlConfigResponse;
import com.openvie.api.workflow.dto.WorkflowDtos.StartProcessRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.StartProcessResponse;
import com.openvie.api.workflow.dto.WorkflowDtos.UpdateSlConfigRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.WorkflowProcessView;
import com.openvie.api.workflow.dto.WorkflowDtos.WorkspaceTaskView;
import com.openvie.api.workflow.dto.WorkflowDtos.WorkspaceTasks;
import com.openvie.api.workflow.service.WorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Approval workflow surface (issue #8). Every workspace scope comes from the
 * request attributes written by JwtAuthFilter (live membership re-resolved on
 * each request), never from a client-supplied workspace.
 *
 * <p>Task identifiers are resolved globally and then permission-checked, so a
 * caller acting on a task of a foreign workspace gets an explicit 403. MEMBER
 * submits flows but never appears in a task's candidate group.
 */
@Tag(name = "Approval workflow", description = "Approval processes, tasks, and SLA configuration per workspace")
@RestController
@Slf4j(topic = "WORKFLOW")
@SecurityRequirement(name = "bearerAuth")
public class WorkflowController extends BaseController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /** Starts (submits) the approval flow for a document. Any workspace member may submit. */
    @Operation(summary = "Start the approval process for a document",
            description = "Submits a COMPLETED document into the approval flow. Every workspace member may submit. "
                    + "The sensitivity scoring runs synchronously: the answer carries the level decision, the "
                    + "sensitive flag, and the policy version; plain workspace submissions by reviewers settle "
                    + "immediately with `autoApproved: true`.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Process started, or settled immediately for a reviewer submitting a plain workspace document (see autoApproved)"),
            @ApiResponse(responseCode = "400", description = "Invalid document state or publish target",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the workspace",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Document not found in the workspace",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/api/v1/workflow/processes")
    public ResponseEntity<StartProcessResponse> start(@Valid @RequestBody StartProcessRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(workflowService.start(getTenantId(httpRequest), getUserId(httpRequest), request));
    }

    @Operation(summary = "List approval processes of the workspace",
            description = "Visible processes for the caller. `includeFinished` adds completed ones. Without `page`/`size` "
                    + "the full list is returned; otherwise the result is server-paged with `X-Total-Count`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Process page (full or paged) with X-Total-Count on the paged shape", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Page/size out of bounds (page ≥ 0, size 1–100)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/api/v1/workflow/processes")
    public ResponseEntity<List<WorkflowProcessView>> processes(
            @RequestParam(name = "includeFinished", required = false, defaultValue = "false")
            boolean includeFinished,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            HttpServletRequest httpRequest) {
        if (page == null && size == null) {
            return ResponseEntity.ok(workflowService.listProcesses(
                    getTenantId(httpRequest), getUserId(httpRequest), includeFinished));
        }
        ProcessListResult result = workflowService.listProcessesPage(
                getTenantId(httpRequest), getUserId(httpRequest), includeFinished, page, size);
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(result.totalCount()))
                .body(result.processes());
    }

    @Operation(summary = "Get one approval process",
            description = "Detail view of a process; tasks of a foreign workspace answer an explicit 403.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Process detail", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "403", description = "The process belongs to another workspace",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Process not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/api/v1/workflow/processes/{processInstanceId}")
    public ResponseEntity<ProcessDetail> process(@PathVariable UUID processInstanceId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowService.getProcess(
                getTenantId(httpRequest), getUserId(httpRequest), processInstanceId));
    }

    /** Who submitted, claimed, approved, rejected, or was reminded — and when. */
    @Operation(summary = "Get the approval log of a process",
            description = "Who submitted, claimed, approved, rejected, or was reminded — and when.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Process log", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "403", description = "The process belongs to another workspace",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Process not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/api/v1/workflow/processes/{processInstanceId}/log")
    public ResponseEntity<ProcessLog> processLog(@PathVariable UUID processInstanceId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowService.processLog(
                getTenantId(httpRequest), getUserId(httpRequest), processInstanceId));
    }

    @Operation(summary = "List the caller's approval tasks",
            description = "Open tasks grouped by waiting/claimed buckets for the caller's active workspace.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task inbox", useReturnTypeSchema = true)
    })
    @GetMapping("/api/v1/workflow/tasks")
    public ResponseEntity<WorkspaceTasks> tasks(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowService.listTasks(
                getTenantId(httpRequest), getUserId(httpRequest)));
    }

    @Operation(summary = "Claim an approval task",
            description = "Assigns the task to the caller; tasks are candidate-grouped to workspace admins (and the "
                    + "org owner for escalated levels). Task ids resolve globally and are permission-checked afterwards.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task claimed", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "403", description = "Not a candidate of the task, or the task belongs to another workspace",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Task not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Task already claimed by another approver",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/api/v1/workflow/tasks/{taskId}/claim")
    public ResponseEntity<WorkspaceTaskView> claim(@PathVariable UUID taskId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowService.claim(
                getTenantId(httpRequest), getUserId(httpRequest), taskId));
    }

    /** Approve, reject with reason, or hand the level 1 task off to level 2. */
    @Operation(summary = "Complete an approval task",
            description = "Approve, reject with a required reason, or hand the level 1 task off to level 2. "
                    + "Only the claimant (or the owner picking up an escalated task) may complete.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Decision recorded; the response carries the settled document status", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "A rejection requires a reason; only level 1 tasks can be handed off",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Task belongs to another workspace or was claimed by another approver",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Task not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/api/v1/workflow/tasks/{taskId}/complete")
    public ResponseEntity<CompleteTaskResponse> complete(@PathVariable UUID taskId,
            @Valid @RequestBody CompleteTaskRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowService.complete(
                getTenantId(httpRequest), getUserId(httpRequest), taskId, request));
    }

    @Operation(summary = "Update the approval SLA configuration (owner only)",
            description = "Sets the reminder and escalation durations of the organization's approval SLA.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Effective SLA saved", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Escalation must not come before the reminder",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only the organization owner can change the approval SLA",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/api/v1/workflow/sla-config")
    @PreAuthorize("hasRole('ORG_OWNER')")
    public ResponseEntity<SlConfigResponse> updateSlConfig(@Valid @RequestBody UpdateSlConfigRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowService.updateSlaConfig(
                getTenantId(httpRequest), getUserId(httpRequest), request));
    }

    @Operation(summary = "Read the effective approval SLA",
            description = "The SLA the workspace's processes currently run with.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Effective SLA", useReturnTypeSchema = true)
    })
    @GetMapping("/api/v1/workflow/sla-config")
    public ResponseEntity<SlConfigResponse> slaConfig(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowService.effectiveSlaConfig(
                getTenantId(httpRequest), getUserId(httpRequest)));
    }
}
