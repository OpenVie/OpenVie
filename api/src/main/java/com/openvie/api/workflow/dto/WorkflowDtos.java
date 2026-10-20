package com.openvie.api.workflow.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Wire surface of the approval workflow (issue #8). */
public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    public record StartProcessRequest(
            @NotNull UUID documentId,
            @NotBlank @Pattern(regexp = "workspace|shared|public",
                    message = "publishTarget must be workspace, shared, or public")
            String publishTarget) {
    }

    public record StartProcessResponse(
            UUID processInstanceId,
            UUID documentId,
            String documentFileName,
            String publishTarget,
            boolean sensitive,
            int approvalLevel,
            String policyVersion,
            String slaReminder,
            String slaEscalation,
            UUID taskId) {
    }

    public record WorkflowProcessView(
            UUID processInstanceId,
            UUID documentId,
            String documentFileName,
            boolean sensitive,
            int approvalLevel,
            String policyVersion,
            String publishTarget,
            String documentStatus,
            boolean escalated,
            LocalDateTime startedAt,
            LocalDateTime endedAt) {
    }

    public record ProcessDetail(
            WorkflowProcessView process,
            List<WorkspaceTaskView> activeTasks) {
    }

    public record WorkspaceTaskView(
            UUID taskId,
            String taskName,
            String taskDefinitionKey,
            Integer approvalLevel,
            String candidateGroup,
            String assignee,
            UUID processInstanceId,
            UUID documentId,
            String documentFileName,
            LocalDateTime createdAt) {
    }

    public record WorkspaceTasks(List<WorkspaceTaskView> assignedToMe, List<WorkspaceTaskView> groupTasks) {
    }

    public record CompleteTaskRequest(
            @NotBlank @Pattern(regexp = "APPROVE|REJECT|ESCALATE", message = "decision must be APPROVE, REJECT, or ESCALATE")
            String decision,
            @Size(max = 500, message = "reason must not exceed 500 characters")
            String reason) {
    }

    public record CompleteTaskResponse(
            UUID taskId,
            String decision,
            UUID processInstanceId,
            UUID documentId,
            boolean processEnded,
            String documentStatus,
            UUID nextTaskId) {
    }

    public record SlConfigResponse(
            Integer reminderMinutes,
            Integer escalationMinutes,
            Integer defaultReminderMinutes,
            Integer defaultEscalationMinutes,
            LocalDateTime updatedAt) {
    }

    public record UpdateSlConfigRequest(
            @NotNull @Min(1) @Max(20160) Integer reminderMinutes,
            @NotNull @Min(1) @Max(20160) Integer escalationMinutes) {
    }
}
