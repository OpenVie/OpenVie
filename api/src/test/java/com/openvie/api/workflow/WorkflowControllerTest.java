package com.openvie.api.workflow;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.openvie.api.bootstrap.config.GlobalExceptionHandler;
import com.openvie.api.workflow.controller.WorkflowController;
import com.openvie.api.workflow.dto.WorkflowDtos.CompleteTaskRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.CompleteTaskResponse;
import com.openvie.api.workflow.dto.WorkflowDtos.StartProcessRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.StartProcessResponse;
import com.openvie.api.workflow.dto.WorkflowDtos.WorkspaceTasks;
import com.openvie.api.workflow.service.WorkflowService;
import org.springframework.security.access.AccessDeniedException;

/** HTTP shape of the workflow surface; RBAC lives in the service tests. */
class WorkflowControllerTest {

    private final WorkflowService service = org.mockito.Mockito.mock(WorkflowService.class);
    private final HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(request.getAttribute("tenantId")).thenReturn(tenantId.toString());
        when(request.getAttribute("userId")).thenReturn(userId.toString());
        mvc = MockMvcBuilders.standaloneSetup(new WorkflowController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void startReturnsTheLevelDecision() throws Exception {
        when(service.start(tenantId, userId, new StartProcessRequest(documentId, "workspace"))).thenReturn(
                new StartProcessResponse(UUID.randomUUID(), documentId, "weekly-notes.docx",
                        "workspace", false, 1, "sensitive-keyword-v1", "PT24H", "PT48H",
                        UUID.randomUUID()));

        mvc.perform(post("/api/v1/workflow/processes")
                        .requestAttr("tenantId", tenantId.toString())
                        .requestAttr("userId", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"documentId":"%s","publishTarget":"workspace"}
                                """.formatted(documentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.approvalLevel").value(1))
                .andExpect(jsonPath("$.sensitive").value(false))
                .andExpect(jsonPath("$.policyVersion").value("sensitive-keyword-v1"));
    }

    @Test
    void startValidatesThePublishTargetAndDocumentId() throws Exception {
        mvc.perform(post("/api/v1/workflow/processes")
                        .requestAttr("tenantId", tenantId.toString())
                        .requestAttr("userId", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":null,\"publishTarget\":\"bananas\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completeRejectsReasonValidationAndMapsRuleViolations() throws Exception {
        when(service.complete(org.mockito.ArgumentMatchers.eq(tenantId),
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.argThat(req -> "REJECT".equals(req.decision())
                        && req.reason() == null)))
                .thenThrow(new com.openvie.api.common.exception.custom.BadRequestException(
                        "A rejection requires a reason"));

        mvc.perform(post("/api/v1/workflow/tasks/{taskId}/complete", UUID.randomUUID())
                        .requestAttr("tenantId", tenantId.toString())
                        .requestAttr("userId", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"reason\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossWorkspaceTaskAccessSurfacesAsForbidden() throws Exception {
        when(service.claim(org.mockito.ArgumentMatchers.eq(tenantId),
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AccessDeniedException("This approval task belongs to another workspace"));

        mvc.perform(post("/api/v1/workflow/tasks/{taskId}/claim", UUID.randomUUID())
                        .requestAttr("tenantId", tenantId.toString())
                        .requestAttr("userId", userId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void taskListsCarryBothViews() throws Exception {
        when(service.listTasks(tenantId, userId)).thenReturn(new WorkspaceTasks(
                java.util.List.of(), java.util.List.of()));

        mvc.perform(get("/api/v1/workflow/tasks")
                        .requestAttr("tenantId", tenantId.toString())
                        .requestAttr("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedToMe").isArray())
                .andExpect(jsonPath("$.groupTasks").isArray());
    }

    @Test
    void slaConfigUpdateReturnsTheEffectiveValues() throws Exception {
        when(service.updateSlaConfig(org.mockito.ArgumentMatchers.eq(tenantId),
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.argThat(
                        req -> req.reminderMinutes() == 30 && req.escalationMinutes() == 60)))
                .thenReturn(new com.openvie.api.workflow.dto.WorkflowDtos.SlConfigResponse(
                        30, 60, 1440, 2880, java.time.LocalDateTime.now()));

        mvc.perform(put("/api/v1/workflow/sla-config")
                        .requestAttr("tenantId", tenantId.toString())
                        .requestAttr("userId", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reminderMinutes\":30,\"escalationMinutes\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminderMinutes").value(30))
                .andExpect(jsonPath("$.escalationMinutes").value(60));
    }

    @Test
    void slaConfigValuesAreBounded() throws Exception {
        mvc.perform(put("/api/v1/workflow/sla-config")
                        .requestAttr("tenantId", tenantId.toString())
                        .requestAttr("userId", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reminderMinutes\":0,\"escalationMinutes\":60}"))
                .andExpect(status().isBadRequest());
    }
}
