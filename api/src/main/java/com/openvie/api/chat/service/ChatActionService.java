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

package com.openvie.api.chat.service;

import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.AppActionOffer;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstallOutcome;
import com.openvie.api.chat.dto.ChatDtos.AssistantMessageResponse;
import com.openvie.api.chat.model.ChatMessage;
import com.openvie.api.chat.query.BizAppIntentRouter;
import com.openvie.api.chat.model.ChatSession;
import com.openvie.api.chat.repository.ChatMessageRepository;
import com.openvie.api.chat.repository.ChatSessionRepository;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.event.AuditLogEvent;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative execution service for conversational BizApp action cards (W2.2).
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "CHAT-ACTION")
public class ChatActionService {

    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    private final BizAppRuntimeApi bizAppRuntimeApi;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public static final Set<String> SUPPORTED_ACTION_KINDS = Set.of(
            "bizapp.install",
            "bizapp.uninstall",
            "bizapp.draft",
            "bizapp.record.create",
            "bizapp.record.update",
            "bizapp.record.archive",
            "bizapp.record.submit"
    );

    @Value("${app.chat.bizapp-actions.enabled:false}")
    private boolean actionsEnabled = false;

    @Value("${app.chat.bizapp-records.enabled:false}")
    private boolean recordActionsEnabled = false;

    private final ConcurrentHashMap<String, Object> actionLocks = new ConcurrentHashMap<>();

    public void setActionsEnabled(boolean actionsEnabled) {
        this.actionsEnabled = actionsEnabled;
    }

    public void setRecordActionsEnabled(boolean recordActionsEnabled) {
        this.recordActionsEnabled = recordActionsEnabled;
    }

    @Transactional
    public AssistantMessageResponse executeAction(
            UUID tenantId,
            UUID orgId,
            UUID actorId,
            String callerRole,
            UUID messageId,
            UUID actionId
    ) {
        return executeAction(tenantId, orgId, actorId, callerRole, messageId != null ? messageId.toString() : null, actionId);
    }

    @Transactional
    public AssistantMessageResponse executeAction(
            UUID tenantId,
            UUID orgId,
            UUID actorId,
            String callerRole,
            String messageId,
            UUID actionId
    ) {
        if (!actionsEnabled) {
            throw new ResourceNotFoundException("BizApp actions are disabled on this installation");
        }

        Object lock = actionLocks.computeIfAbsent(actionId.toString(), k -> new Object());
        synchronized (lock) {
            ChatMessage message = null;
            if (messageId != null && !messageId.isBlank()) {
                try {
                    UUID parsedId = UUID.fromString(messageId.trim());
                    message = messageRepository.findById(parsedId).orElse(null);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (message == null) {
                message = messageRepository.findByTenantIdAndActionId(tenantId, actionId.toString())
                        .orElseThrow(() -> new ResourceNotFoundException("Message or action not found: " + actionId));
            }
            if (!tenantId.equals(message.getTenantId())) {
                throw new ResourceNotFoundException("Message not found in active workspace");
            }

            if (!"assistant".equalsIgnoreCase(message.getRole())) {
                throw new ConflictException("Actions can only be executed on assistant messages");
            }

            Map<String, Object> action = new LinkedHashMap<>(message.getAction() != null ? message.getAction() : Map.of());
            if (action.isEmpty()) {
                throw new ResourceNotFoundException("No action attached to message " + messageId);
            }

            String cardActionId = String.valueOf(action.get("actionId"));
            if (!actionId.toString().equalsIgnoreCase(cardActionId)) {
                throw new ConflictException("Action ID mismatch on message");
            }

            String currentStatus = String.valueOf(action.get("status"));
            if ("EXECUTED".equalsIgnoreCase(currentStatus)) {
                // Idempotent: return existing executed assistant result
                Object existingResult = action.get("result");
                String replyContent = existingResult instanceof Map<?, ?> m && m.get("message") != null
                        ? String.valueOf(m.get("message"))
                        : "Action already executed";
                return new AssistantMessageResponse("assistant", replyContent, List.of(), action, List.of());
            }

            if (!"PROPOSED".equalsIgnoreCase(currentStatus) && !"FAILED".equalsIgnoreCase(currentStatus)) {
                throw new ConflictException("Action is not in PROPOSED or FAILED state (current: " + currentStatus + ")");
            }

            String kind = String.valueOf(action.get("kind")).toLowerCase(Locale.ROOT);
            boolean recordAction = kind.startsWith("bizapp.record.");

            // Server-authoritative permission check: lifecycle/configuration
            // actions stay owner-only. Record actions are delegated to the
            // app manifest RBAC inside executeRecordCommand, so members can
            // create records in apps that explicitly grant them write access.
            if (!recordAction && !"ORG_OWNER".equals(callerRole)) {
                action.put("status", "DENIED");
                message.setAction(action);
                messageRepository.save(message);

                eventPublisher.publishEvent(AuditLogEvent.builder(this)
                        .tenantId(tenantId)
                        .userId(actorId)
                        .action(LogAction.CHAT_APP_ACTION_DENIED)
                        .resourceType("bizapp_action")
                        .resourceId(actionId)
                        .metadata(Map.of("actionId", actionId.toString(), "reason", "REQUIRES_ORG_OWNER"))
                        .build());

                if (meterRegistry != null) {
                    meterRegistry.counter("chat_action_execute_total", "action", kind, "outcome", "denied").increment();
                }

                throw new AccessDeniedException("Only organization owners may execute this app action");
            }

            String appKind = String.valueOf(action.get("appKind"));
            String appName = String.valueOf(action.getOrDefault("appName", appKind));
            int version = action.get("version") instanceof Number n ? n.intValue() : 1;
            String description = String.valueOf(action.getOrDefault("description", ""));

            if (!SUPPORTED_ACTION_KINDS.contains(kind)) {
                throw new ConflictException("Unsupported executable action kind: " + kind);
            }

            if (recordAction && !recordActionsEnabled) {
                throw new ResourceNotFoundException("BizApp record actions are disabled on this installation");
            }

            String outcomeMessage;
            Map<String, Object> resultMap = new LinkedHashMap<>();

            if ("bizapp.draft".equalsIgnoreCase(kind)) {
                Map<?, ?> payload = action.get("payload") instanceof Map<?, ?> p ? p : Map.of();
                UUID targetSessionId = payload.get("sessionId") != null && !String.valueOf(payload.get("sessionId")).isBlank()
                        ? UUID.fromString(String.valueOf(payload.get("sessionId")))
                        : message.getSessionId();
                String hint = payload.get("hint") != null
                        ? String.valueOf(payload.get("hint"))
                        : (appName != null && !appName.isBlank() && !"null".equalsIgnoreCase(appName) ? appName : null);

                BizAppRuntimeApi.DraftOutcome outcome;
                try {
                    outcome = bizAppRuntimeApi.createDraftFromSession(tenantId, orgId, actorId, targetSessionId, hint);
                } catch (Exception ex) {
                    log.warn("Draft creation failed: {}", ex.getMessage());
                    action.put("status", "FAILED");
                    action.put("executedBy", actorId.toString());
                    action.put("executedAt", Instant.now().toString());
                    action.put("result", Map.of("error", ex.getMessage()));
                    message.setAction(action);
                    messageRepository.save(message);

                    if (meterRegistry != null) {
                        meterRegistry.counter("chat_action_execute_total", "action", kind, "outcome", "failed").increment();
                    }
                    throw ex;
                }

                if (outcome.draftId() == null) {
                    throw new ConflictException("Draft generation failed: " + String.join(", ", outcome.errors()));
                }

                outcomeMessage = "Bản thảo ứng dụng '" + outcome.title() + "' đã được tạo thành công.";
                resultMap.put("draftId", outcome.draftId().toString());
                resultMap.put("title", outcome.title());
                resultMap.put("entities", outcome.entities());
                resultMap.put("message", outcomeMessage);
                resultMap.put("composerUrl", "/admin/apps/compose/" + outcome.draftId());
            } else if ("bizapp.install".equalsIgnoreCase(kind) || "bizapp.uninstall".equalsIgnoreCase(kind)) {
                Map<?, ?> payload = action.get("payload") instanceof Map<?, ?> p ? p : Map.of();
                String templateRef = payload.get("templateRef") != null ? String.valueOf(payload.get("templateRef")) : null;
                if (templateRef == null || templateRef.isBlank()) {
                    templateRef = "platform:" + appKind;
                }

                List<InstalledAppSummary> installed = bizAppRuntimeApi.listInstalledSummary(tenantId);
                if ("bizapp.install".equalsIgnoreCase(kind)) {
                    boolean alreadyInstalled = installed.stream().anyMatch(i -> i.kind().equalsIgnoreCase(appKind));
                    if (alreadyInstalled) {
                        throw new ConflictException("App " + appKind + " is already installed in this workspace");
                    }
                } else {
                    boolean isInstalled = installed.stream().anyMatch(i -> i.kind().equalsIgnoreCase(appKind));
                    if (!isInstalled) {
                        throw new ResourceNotFoundException("App " + appKind + " is not installed in this workspace");
                    }
                }

                AppActionOffer offer = new AppActionOffer(
                        actionId, kind, appKind, appName, version, description, "ORG_OWNER", templateRef);

                InstallOutcome outcome;
                try {
                    outcome = bizAppRuntimeApi.executeOffer(tenantId, orgId, actorId, offer);
                } catch (Exception ex) {
                    log.warn("Action execution failed: {}", ex.getMessage());
                    action.put("status", "FAILED");
                    action.put("executedBy", actorId.toString());
                    action.put("executedAt", Instant.now().toString());
                    action.put("result", Map.of("error", ex.getMessage()));
                    message.setAction(action);
                    messageRepository.save(message);

                    if (meterRegistry != null) {
                        meterRegistry.counter("chat_action_execute_total", "action", kind, "outcome", "failed").increment();
                    }
                    throw ex;
                }

                if (!outcome.success()) {
                    throw new IllegalStateException("Offer execution failed: " + outcome.message());
                }

                if (outcome.installationId() != null) {
                    resultMap.put("installationId", outcome.installationId().toString());
                }
                outcomeMessage = outcome.message();
                resultMap.put("message", outcomeMessage);
            } else {
                // Record actions: create, update, archive, submit
                String expectedHash = String.valueOf(action.get("payloadHash"));
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = action.get("fields") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                String actualHash = BizAppIntentRouter.computePayloadHash(fields);
                if (expectedHash != null && !expectedHash.equals("null") && !expectedHash.equals(actualHash)) {
                    throw new ConflictException("Payload hash mismatch; action card has been tampered with");
                }

                UUID recordId = null;
                if (action.get("recordId") != null && !String.valueOf(action.get("recordId")).isBlank()) {
                    try {
                        recordId = UUID.fromString(String.valueOf(action.get("recordId")));
                    } catch (IllegalArgumentException ignored) {}
                }

                BizAppRuntimeApi.RecordCommand command = new BizAppRuntimeApi.RecordCommand(
                        kind, appKind, String.valueOf(action.get("entity")), recordId, fields, expectedHash);

                BizAppRuntimeApi.RecordCommandOutcome outcome;
                try {
                    outcome = bizAppRuntimeApi.executeRecordCommand(tenantId, orgId, actorId, callerRole, command);
                } catch (Exception ex) {
                    log.warn("Record action execution failed: {}", ex.getMessage());
                    // A manifest RBAC denial is a distinct outcome from a broken action.
                    action.put("status", isAccessDenied(ex) ? "DENIED" : "FAILED");
                    action.put("executedBy", actorId.toString());
                    action.put("executedAt", Instant.now().toString());
                    action.put("result", Map.of("error", ex.getMessage()));
                    message.setAction(action);
                    messageRepository.save(message);

                    if (meterRegistry != null) {
                        meterRegistry.counter("chat_action_execute_total", "action", kind, "outcome", "failed").increment();
                    }
                    throw ex;
                }

                if (!outcome.success()) {
                    throw new IllegalStateException("Record command failed: " + outcome.message());
                }

                if (outcome.recordId() != null) {
                    resultMap.put("recordId", outcome.recordId().toString());
                }
                outcomeMessage = outcome.message();
                resultMap.put("message", outcomeMessage);
            }

            action.put("status", "EXECUTED");
            action.put("executedBy", actorId.toString());
            action.put("executedAt", Instant.now().toString());
            action.put("result", resultMap);
            message.setAction(action);
            messageRepository.save(message);

            ChatSession session = sessionRepository.findById(message.getSessionId()).orElseThrow();
            int nextSeq = session.getNextSequenceNumber();
            session.setNextSequenceNumber(nextSeq + 1);
            session.setLastActivityAt(LocalDateTime.now());
            sessionRepository.save(session);

            ChatMessage confirmation = new ChatMessage();
            confirmation.setSessionId(session.getId());
            confirmation.setTenantId(tenantId);
            confirmation.setRole("assistant");
            confirmation.setContent(outcomeMessage);
            confirmation.setCitations(List.of());
            confirmation.setAction(action);
            confirmation.setSequenceNumber(nextSeq);
            messageRepository.save(confirmation);

            eventPublisher.publishEvent(AuditLogEvent.builder(this)
                    .tenantId(tenantId)
                    .userId(actorId)
                    .action(LogAction.CHAT_APP_ACTION_EXECUTED)
                    .resourceType("bizapp_action")
                    .resourceId(actionId)
                    .metadata(Map.of("actionId", actionId.toString(), "kind", kind, "appKind", appKind))
                    .build());

            if (meterRegistry != null) {
                meterRegistry.counter("chat_action_execute_total", "action", kind, "outcome", "success").increment();
            }

            return new AssistantMessageResponse("assistant", confirmation.getContent(), List.of(), confirmation.getAction(), List.of());
        }
    }
    private boolean isAccessDenied(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof AccessDeniedException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

}
