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

    @Value("${app.chat.bizapp-actions.enabled:false}")
    private boolean actionsEnabled = false;

    private final ConcurrentHashMap<String, Object> actionLocks = new ConcurrentHashMap<>();

    public void setActionsEnabled(boolean actionsEnabled) {
        this.actionsEnabled = actionsEnabled;
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
        if (!actionsEnabled) {
            throw new ResourceNotFoundException("BizApp actions are disabled on this installation");
        }

        Object lock = actionLocks.computeIfAbsent(actionId.toString(), k -> new Object());
        synchronized (lock) {
            ChatMessage message = messageRepository.findById(messageId)
                    .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + messageId));

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

            if (!"PROPOSED".equalsIgnoreCase(currentStatus)) {
                throw new ConflictException("Action is not in PROPOSED state (current: " + currentStatus + ")");
            }

            // Server-authoritative permission check: require ORG_OWNER
            if (!"ORG_OWNER".equals(callerRole)) {
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
                    meterRegistry.counter("chat_action_execute_total", "action", String.valueOf(action.get("kind")), "outcome", "denied").increment();
                }

                throw new AccessDeniedException("Only organization owners may execute app actions");
            }

            String kind = String.valueOf(action.get("kind"));
            String appKind = String.valueOf(action.get("appKind"));
            String appName = String.valueOf(action.getOrDefault("appName", appKind));
            int version = action.get("version") instanceof Number n ? n.intValue() : 1;
            String description = String.valueOf(action.getOrDefault("description", ""));

            Map<?, ?> payload = action.get("payload") instanceof Map<?, ?> p ? p : Map.of();
            String templateRef = payload.get("templateRef") != null ? String.valueOf(payload.get("templateRef")) : null;

            if (!"bizapp.install".equalsIgnoreCase(kind) && !"bizapp.uninstall".equalsIgnoreCase(kind)) {
                throw new ConflictException("Unsupported executable action kind: " + kind);
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

            Map<String, Object> resultMap = new LinkedHashMap<>();
            if (outcome.installationId() != null) {
                resultMap.put("installationId", outcome.installationId().toString());
            }
            resultMap.put("message", outcome.message());

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
            confirmation.setContent(outcome.message());
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
}
