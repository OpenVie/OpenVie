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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire surface of the approval workflow (issue #8). */
public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    @Schema(description = "Submits a COMPLETED document into the workspace approval flow.")
    public record StartProcessRequest(
            @Schema(description = "Document to approve; must be COMPLETED and without an open process.") @NotNull UUID documentId,
            @Schema(description = "Where the document is published once approved: `workspace`, `shared`, or `public`.", example = "workspace")
            @NotBlank @Pattern(regexp = "workspace|shared|public",
                    message = "publishTarget must be workspace, shared, or public")
            String publishTarget) {
    }

    @Schema(description = "Result of starting an approval process, including the synchronous sensitivity scoring decision.")
    public record StartProcessResponse(
            UUID processInstanceId,
            UUID documentId,
            String documentFileName,
            String publishTarget,
            @Schema(description = "Whether sensitivity scoring classified the document as sensitive (drives the approval level).")
            boolean sensitive,
            int approvalLevel,
            String policyVersion,
            String slaReminder,
            String slaEscalation,
            @Schema(description = "First approval task; auto-approved starts leave this null.")
            UUID taskId,
            /**
             * True when the submitter's own authority covers the required
             * review (workspace-scoped submission by a workspace admin or the
             * org owner of an unsensitive document): the first task was
             * auto-approved when the flow started and {@link #documentStatus}
             * is already decided.
             */
            @Schema(description = "True when the submitter's own authority covered the required review and the first "
                    + "task was auto-approved at start.")
            boolean autoApproved,
            /** APPROVED when autoApproved, otherwise null while still in review. */
            @Schema(description = "APPROVED when autoApproved; null while the document is still in review.")
            String documentStatus) {
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

    /** One page of the workspace approval journal; totalCount backs X-Total-Count. */
    public record ProcessListResult(List<WorkflowProcessView> processes, long totalCount) {
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

    @Schema(description = "The caller's task inbox: tasks assigned to them plus unclaimed candidate-group tasks.")
    public record WorkspaceTasks(List<WorkspaceTaskView> assignedToMe, List<WorkspaceTaskView> groupTasks) {
    }

    @Schema(description = "An approval decision on a claimed task.")
    public record CompleteTaskRequest(
            @Schema(description = "APPROVE settles the level, REJECT ends the process as REJECTED, ESCALATE hands a "
                    + "level 1 task to level 2.", example = "APPROVE")
            @NotBlank @Pattern(regexp = "APPROVE|REJECT|ESCALATE", message = "decision must be APPROVE, REJECT, or ESCALATE")
            String decision,
            @Schema(description = "Required for REJECT; ignored otherwise, up to 500 characters.")
            @Size(max = 500, message = "reason must not exceed 500 characters")
            String reason) {
    }

    @Schema(description = "Outcome of recording an approval decision.")
    public record CompleteTaskResponse(
            UUID taskId,
            String decision,
            UUID processInstanceId,
            UUID documentId,
            @Schema(description = "True when the decision settled the process (approved, rejected, or last level done).")
            boolean processEnded,
            @Schema(description = "Document status after the decision: APPROVED, REJECTED, or null while still in review.")
            String documentStatus,
            @Schema(description = "Next approval task when the process moved to a further level.")
            UUID nextTaskId) {
    }

    /**
     * One audited event of an approval process. {@code metadata} carries the
     * action's own facts (decision, reason, sensitivity, levels); {@code
     * actorName} is resolved server-side so callers never have to join the
     * identity directory.
     */
    public record ProcessLogEntry(
            Instant occurredAt,
            UUID actorId,
            String actorName,
            String action,
            Map<String, Object> metadata) {
    }

    /** The approval history surface: the process facts plus its audit trail. */
    public record ProcessLog(WorkflowProcessView process, List<ProcessLogEntry> entries) {
    }

    @Schema(description = "The effective approval SLA: configured reminder/escalation minutes plus the org defaults they fall back to.")
    public record SlConfigResponse(
            @Schema(description = "Minutes before an open approval task triggers a reminder.", example = "1440")
            Integer reminderMinutes,
            @Schema(description = "Minutes before an unanswered task escalates to level 2.", example = "4320")
            Integer escalationMinutes,
            Integer defaultReminderMinutes,
            Integer defaultEscalationMinutes,
            LocalDateTime updatedAt) {
    }

    @Schema(description = "Owner-only update of the approval SLA.")
    public record UpdateSlConfigRequest(
            @Schema(description = "Reminder threshold in minutes (1–20160). Escalation must come later.", example = "1440")
            @NotNull @Min(1) @Max(20160) Integer reminderMinutes,
            @Schema(description = "Escalation threshold in minutes (1–20160); must be later than the reminder.", example = "4320")
            @NotNull @Min(1) @Max(20160) Integer escalationMinutes) {
    }
}
