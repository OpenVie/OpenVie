package com.openvie.api.workflow.controller;

import com.openvie.api.common.controller.BaseController;
import com.openvie.api.workflow.dto.WorkflowDtos;
import com.openvie.api.workflow.dto.WorkflowDtos.CompleteTaskRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.CompleteTaskResponse;
import com.openvie.api.workflow.dto.WorkflowDtos.ProcessDetail;
import com.openvie.api.workflow.dto.WorkflowDtos.SlConfigResponse;
import com.openvie.api.workflow.dto.WorkflowDtos.StartProcessRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.StartProcessResponse;
import com.openvie.api.workflow.dto.WorkflowDtos.UpdateSlConfigRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.WorkflowProcessView;
import com.openvie.api.workflow.dto.WorkflowDtos.WorkspaceTaskView;
import com.openvie.api.workflow.dto.WorkflowDtos.WorkspaceTasks;
import com.openvie.api.workflow.service.WorkflowService;
import jakarta.servlet.http.HttpServletRequest;
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
@RestController
@Slf4j(topic = "WORKFLOW")
public class WorkflowController extends BaseController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /** Starts (submits) the approval flow for a document. Any workspace member may submit. */
    @PostMapping("/api/v1/workflow/processes")
    public ResponseEntity<StartProcessResponse> start(@Valid @RequestBody StartProcessRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(workflowService.start(getTenantId(httpRequest), getUserId(httpRequest), request));
    }

    @GetMapping("/api/v1/workflow/processes")
    public ResponseEntity<List<WorkflowProcessView>> processes(
            @RequestParam(name = "includeFinished", required = false, defaultValue = "false")
            boolean includeFinished,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowService.listProcesses(
                getTenantId(httpRequest), getUserId(httpRequest), includeFinished));
    }

    @GetMapping("/api/v1/workflow/processes/{processInstanceId}")
    public ResponseEntity<ProcessDetail> process(@PathVariable UUID processInstanceId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowService.getProcess(
                getTenantId(httpRequest), getUserId(httpRequest), processInstanceId));
    }

    @GetMapping("/api/v1/workflow/tasks")
    public ResponseEntity<WorkspaceTasks> tasks(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowService.listTasks(
                getTenantId(httpRequest), getUserId(httpRequest)));
    }

    @PostMapping("/api/v1/workflow/tasks/{taskId}/claim")
    public ResponseEntity<WorkspaceTaskView> claim(@PathVariable UUID taskId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowService.claim(
                getTenantId(httpRequest), getUserId(httpRequest), taskId));
    }

    /** Approve, reject with reason, or hand the level 1 task off to level 2. */
    @PostMapping("/api/v1/workflow/tasks/{taskId}/complete")
    public ResponseEntity<CompleteTaskResponse> complete(@PathVariable UUID taskId,
            @Valid @RequestBody CompleteTaskRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowService.complete(
                getTenantId(httpRequest), getUserId(httpRequest), taskId, request));
    }

    @PutMapping("/api/v1/workflow/sla-config")
    @PreAuthorize("hasRole('ORG_OWNER')")
    public ResponseEntity<SlConfigResponse> updateSlConfig(@Valid @RequestBody UpdateSlConfigRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowService.updateSlaConfig(
                getTenantId(httpRequest), getUserId(httpRequest), request));
    }

    @GetMapping("/api/v1/workflow/sla-config")
    public ResponseEntity<SlConfigResponse> slaConfig(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(workflowService.effectiveSlaConfig(
                getTenantId(httpRequest), getUserId(httpRequest)));
    }
}
