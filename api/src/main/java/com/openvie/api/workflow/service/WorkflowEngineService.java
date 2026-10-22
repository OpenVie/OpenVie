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

import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.workflow.dto.EngineDtos.EngineClaimRequest;
import com.openvie.api.workflow.dto.EngineDtos.EngineCompleteRequest;
import com.openvie.api.workflow.dto.EngineDtos.EngineDefinitionView;
import com.openvie.api.workflow.dto.EngineDtos.EngineProcessHistoryView;
import com.openvie.api.workflow.dto.EngineDtos.EngineProcessView;
import com.openvie.api.workflow.dto.EngineDtos.EngineTaskView;
import com.openvie.api.workflow.dto.EngineDtos.EngineTasks;
import com.openvie.api.workflow.dto.EngineDtos.StartEngineProcessRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The generic workflow-engine surface (L2 in the integration roadmap): deploy
 * any BPMN definition, start processes with variables, work a generic task
 * inbox, and read history — every operation scoped to the caller's workspace
 * through Flowable's native tenancy (`tenantId` = workspace id).
 *
 * <p>The document-approval flow ({@link WorkflowService}) is one application
 * of this engine. Approval tasks stay journaled by the approval endpoints:
 * this service refuses raw claim/complete on the domain definition so
 * integrators cannot bypass the document journal.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "WORKFLOW-ENGINE")
public class WorkflowEngineService {

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;

    @Transactional
    public EngineDefinitionView deployDefinition(UUID workspaceId, UUID deployerId, String name, String bpmnXml) {
        String xml = bpmnXml == null ? "" : bpmnXml.trim();
        if (!xml.startsWith("<") || !xml.contains("<definitions")) {
            throw new BadRequestException("bpmnXml must be a BPMN 2.0 XML document");
        }
        Deployment deployment = repositoryService.createDeployment()
                .tenantId(workspaceId.toString())
                .name(name)
                .addString(name + ".bpmn20.xml", xml)
                .deploy();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .singleResult();
        String key = definition == null ? null : definition.getKey();
        log.info("Engine definition deployed workspace={} deployment={} key={}",
                workspaceId, deployment.getId(), key);
        return new EngineDefinitionView(
                deployment.getId(),
                key,
                name,
                definition == null ? 1 : definition.getVersion(),
                toDateTime(deployment.getDeploymentTime()),
                true);
    }

    @Transactional(readOnly = true)
    public List<EngineDefinitionView> listDefinitions(UUID workspaceId) {
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(workspaceId.toString())
                .latestVersion()
                .orderByProcessDefinitionName().asc()
                .list();
        return definitions.stream()
                .map(definition -> new EngineDefinitionView(
                        definition.getId(),
                        definition.getKey(),
                        definition.getName() == null ? definition.getKey() : definition.getName(),
                        definition.getVersion(),
                        deploymentTime(definition),
                        true))
                .toList();
    }

    /**
     * Flowable exposes the deployment timestamp on the deployment, not the
     * process definition; one lookup keeps the definition view honest.
     */
    private LocalDateTime deploymentTime(ProcessDefinition definition) {
        Deployment deployment = repositoryService.createDeploymentQuery()
                .deploymentId(definition.getDeploymentId())
                .singleResult();
        return deployment == null ? null : toDateTime(deployment.getDeploymentTime());
    }

    @Transactional
    public EngineProcessView startProcess(UUID workspaceId, UUID initiatorId, StartEngineProcessRequest request) {
        ProcessDefinition definition = resolveDefinition(workspaceId, request.definitionKey());
        Map<String, Object> variables = request.variables() == null
                ? Map.of()
                : new HashMap<>(request.variables());

        var startBuilder = runtimeService.createProcessInstanceBuilder()
                .processDefinitionId(definition.getId())
                .tenantId(workspaceId.toString())
                .variables(variables);
        if (request.businessKey() != null && !request.businessKey().isBlank()) {
            startBuilder.businessKey(request.businessKey());
        }
        var instance = startBuilder.start();
        return new EngineProcessView(
                instance.getId(),
                definition.getKey(),
                definition.getName(),
                request.businessKey(),
                toDateTime(instance.getStartTime()),
                runtimeService.getVariables(instance.getId()));
    }

    @Transactional(readOnly = true)
    public List<EngineProcessView> listProcesses(UUID workspaceId, String definitionKey, boolean latestVersionOnly) {
        var query = runtimeService.createProcessInstanceQuery()
                .processInstanceTenantId(workspaceId.toString());
        ProcessDefinition definition = null;
        if (definitionKey != null && !definitionKey.isBlank()) {
            query.processDefinitionKey(definitionKey);
            definition = resolveDefinition(workspaceId, definitionKey);
            if (latestVersionOnly) {
                query.processDefinitionId(definition.getId());
            }
        }
        query.orderByStartTime().desc();
        List<EngineProcessView> views = new ArrayList<>();
        for (var instance : query.includeProcessVariables().list()) {
            views.add(new EngineProcessView(
                    instance.getId(),
                    instance.getProcessDefinitionKey(),
                    definitionName(instance.getProcessDefinitionId()),
                    instance.getBusinessKey(),
                    toDateTime(instance.getStartTime()),
                    instance.getProcessVariables()));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public EngineTasks listTasks(UUID workspaceId, String definitionKey, boolean unassignedOnly,
                                 UUID assigneeId, int limit) {
        var query = taskService.createTaskQuery()
                .taskTenantId(workspaceId.toString());
        if (definitionKey != null && !definitionKey.isBlank()) {
            query.processDefinitionKey(definitionKey);
        }
        if (unassignedOnly) {
            query.taskUnassigned();
        }
        if (assigneeId != null) {
            query.taskAssignee(assigneeId.toString());
        }
        query.orderByTaskCreateTime().desc();
        long total = query.count();
        List<EngineTaskView> views = new ArrayList<>();
        for (Task task : query.listPage(0, Math.max(1, Math.min(limit, 100)))) {
            String taskDefinitionKey = definitionKey(task.getProcessDefinitionId());
            views.add(new EngineTaskView(
                    task.getId(),
                    task.getName(),
                    task.getAssignee(),
                    task.getProcessInstanceId(),
                    taskDefinitionKey,
                    WorkflowService.PROCESS_KEY.equals(taskDefinitionKey),
                    toDateTime(task.getCreateTime()),
                    toDateTime(task.getDueDate())));
        }
        return new EngineTasks(views, total);
    }

    @Transactional
    public Map<String, Object> claimTask(UUID workspaceId, UUID callerId, UUID taskId, EngineClaimRequest request) {
        Task task = requireTask(workspaceId, taskId);
        requireNotApprovalDomain(task);
        String assignee = request != null && request.assignee() != null && !request.assignee().isBlank()
                ? request.assignee()
                : callerId.toString();
        if (task.getAssignee() != null && !task.getAssignee().equals(assignee)) {
            throw new ConflictException("Task already claimed by another assignee");
        }
        taskService.claim(task.getId(), assignee);
        log.info("Engine task claimed taskId={} assignee={}", taskId, assignee);
        return Map.of("id", task.getId(), "assignee", assignee);
    }

    @Transactional
    public Map<String, Object> completeTask(UUID workspaceId, UUID callerId, UUID taskId,
                                            EngineCompleteRequest request) {
        Task task = requireTask(workspaceId, taskId);
        requireNotApprovalDomain(task);
        Map<String, Object> variables = request == null || request.variables() == null
                ? Map.of()
                : request.variables();
        taskService.complete(task.getId(), variables);
        log.info("Engine task completed taskId={}", taskId);
        HistoricProcessInstance historic = historyInstance(workspaceId, task.getProcessInstanceId());
        boolean ended = historic != null && historic.getEndTime() != null;
        return Map.of(
                "id", task.getId(),
                "processId", task.getProcessInstanceId(),
                "processEnded", ended);
    }

    @Transactional(readOnly = true)
    public EngineProcessHistoryView processHistory(UUID workspaceId, UUID processId) {
        HistoricProcessInstance historic = historyInstance(workspaceId, processId.toString());
        if (historic == null) {
            throw new ResourceNotFoundException("Process not found");
        }
        boolean ended = historic.getEndTime() != null;
        return new EngineProcessHistoryView(
                historic.getId(),
                historic.getProcessDefinitionKey(),
                historic.getBusinessKey(),
                toDateTime(historic.getStartTime()),
                toDateTime(historic.getEndTime()),
                ended,
                ended ? historic.getDurationInMillis() : null,
                historic.getProcessVariables());
    }

    private HistoricProcessInstance historyInstance(UUID workspaceId, String processId) {
        return historyService.createHistoricProcessInstanceQuery()
                .processInstanceTenantId(workspaceId.toString())
                .processInstanceId(processId)
                .includeProcessVariables()
                .singleResult();
    }

    private Task requireTask(UUID workspaceId, UUID taskId) {
        if (taskId == null) {
            throw new BadRequestException("Task id is required");
        }
        Task task = taskService.createTaskQuery()
                .taskId(taskId.toString())
                .singleResult();
        if (task == null) {
            throw new ResourceNotFoundException("Task not found");
        }
        if (!workspaceId.toString().equals(task.getTenantId())) {
            // Never reveal cross-workspace task existence.
            throw new AccessDeniedException("Task is outside the active workspace");
        }
        return task;
    }

    private void requireNotApprovalDomain(Task task) {
        String definitionKey = definitionKey(task.getProcessDefinitionId());
        if (WorkflowService.PROCESS_KEY.equals(definitionKey)) {
            throw new ConflictException(
                    "Approval tasks belong to the document journal — use the approval endpoints");
        }
    }

    private ProcessDefinition resolveDefinition(UUID workspaceId, String definitionKey) {
        if (definitionKey == null || definitionKey.isBlank()) {
            throw new BadRequestException("Definition key is required");
        }
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(workspaceId.toString())
                .processDefinitionKey(definitionKey)
                .latestVersion()
                .singleResult();
        if (definition == null) {
            definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionTenantId(workspaceId.toString())
                    .processDefinitionId(definitionKey)
                    .singleResult();
        }
        if (definition == null) {
            throw new ResourceNotFoundException(
                    "Definition not found in this workspace: " + definitionKey);
        }
        return definition;
    }

    private String definitionKey(String processDefinitionId) {
        if (processDefinitionId == null) {
            return null;
        }
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId)
                .singleResult();
        return definition == null ? null : definition.getKey();
    }

    private String definitionName(String processDefinitionId) {
        if (processDefinitionId == null) {
            return null;
        }
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId)
                .singleResult();
        return definition == null ? null : definition.getName();
    }

    private static LocalDateTime toDateTime(Date date) {
        return date == null
                ? null
                : LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }
}
