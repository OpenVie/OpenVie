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

package com.openvie.api.integration.webhook;

import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.event.AuditLogEvent;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.integration.IntegrationCredentials;
import com.openvie.api.notification.service.CredentialCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook endpoint lifecycle plus the outbox bookkeeping integrators use to
 * understand what did (or did not) reach their systems.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "WEBHOOK")
public class WebhookService {

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final CredentialCipher cipher;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public WebhookDtos.CreatedWebhook create(
            UUID orgId, UUID auditWorkspaceId, UUID creatorId, WebhookDtos.CreateWebhookRequest request) {
        if (!cipher.available()) {
            throw new ConflictException(
                    "Webhook signing secrets require app.notifications.encryption-key to be configured");
        }
        String name = request.name().trim();
        if (name.isEmpty()) {
            throw new BadRequestException("Name is required");
        }
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setOrgId(orgId);
        endpoint.setName(name);
        endpoint.setUrl(request.url().trim());
        if (request.events() != null && !request.events().isEmpty()) {
            request.events().forEach(eventType -> {
                if (eventType == null || !eventType.matches("[a-z][a-z0-9._-]{1,148}")) {
                    throw new BadRequestException("Invalid event type filter: " + eventType);
                }
            });
            endpoint.setEvents(List.copyOf(request.events()));
        }
        String plaintext = IntegrationCredentials.randomSecret(IntegrationCredentials.WEBHOOK_SECRET_MARKER);
        endpoint.setSecretEncrypted(cipher.encrypt(plaintext));
        endpoint.setCreatedBy(creatorId);
        endpointRepository.save(endpoint);

        eventPublisher.publishEvent(AuditLogEvent.builder(this)
                .tenantId(auditWorkspaceId)
                .userId(creatorId)
                .action(LogAction.WEBHOOK_ENDPOINT_CHANGED)
                .resourceType("WebhookEndpoint")
                .resourceId(endpoint.getId())
                .metadata(Map.of(
                        "change", "created",
                        "name", name,
                        "url", endpoint.getUrl()))
                .build());

        log.info("Webhook endpoint created id={} org={}", endpoint.getId(), orgId);
        return new WebhookDtos.CreatedWebhook(endpoint.getId(), plaintext, toView(endpoint));
    }

    @Transactional(readOnly = true)
    public List<WebhookDtos.WebhookEndpointView> list(UUID orgId) {
        return endpointRepository.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public void delete(UUID orgId, UUID auditWorkspaceId, UUID actorId, UUID endpointId) {
        WebhookEndpoint endpoint = endpointRepository.findById(endpointId)
                .filter(row -> row.getOrgId().equals(orgId))
                .orElseThrow(() -> new ResourceNotFoundException("Webhook endpoint not found"));
        endpointRepository.delete(endpoint);
        eventPublisher.publishEvent(AuditLogEvent.builder(this)
                .tenantId(auditWorkspaceId)
                .userId(actorId)
                .action(LogAction.WEBHOOK_ENDPOINT_CHANGED)
                .resourceType("WebhookEndpoint")
                .resourceId(endpoint.getId())
                .metadata(Map.of("change", "deleted", "name", endpoint.getName()))
                .build());
        log.info("Webhook endpoint deleted id={}", endpoint.getId());
    }

    @Transactional
    public void testSend(UUID orgId, UUID actorId, UUID endpointId) {
        WebhookEndpoint endpoint = endpointRepository.findById(endpointId)
                .filter(row -> row.getOrgId().equals(orgId))
                .orElseThrow(() -> new ResourceNotFoundException("Webhook endpoint not found"));
        if (!"ACTIVE".equals(endpoint.getStatus())) {
            throw new ConflictException("Webhook endpoint is disabled");
        }
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setEventId(UUID.randomUUID());
        delivery.setEndpointId(endpoint.getId());
        delivery.setOrgId(orgId);
        delivery.setEventType("webhook.test");
        delivery.setPayload(Map.of("message", "This is a test event from OpenVie."));
        delivery.setNextAttemptAt(LocalDateTime.now());
        deliveryRepository.save(delivery);
        log.info("Webhook test delivery scheduled endpoint={}", endpointId);
    }

    @Transactional(readOnly = true)
    public List<WebhookDtos.WebhookDeliveryView> deliveries(UUID orgId, UUID endpointId) {
        endpointRepository.findById(endpointId)
                .filter(row -> row.getOrgId().equals(orgId))
                .orElseThrow(() -> new ResourceNotFoundException("Webhook endpoint not found"));
        return deliveryRepository.findByEndpointIdOrderByCreatedAtDesc(endpointId).stream()
                .limit(20)
                .map(row -> new WebhookDtos.WebhookDeliveryView(
                        row.getId(),
                        row.getEventId(),
                        row.getEventType(),
                        row.getStatus(),
                        row.getAttemptCount(),
                        row.getLastHttpStatus(),
                        row.getLastError(),
                        row.getCreatedAt(),
                        row.getDeliveredAt(),
                        row.getNextAttemptAt()))
                .toList();
    }

    private WebhookDtos.WebhookEndpointView toView(WebhookEndpoint endpoint) {
        return new WebhookDtos.WebhookEndpointView(
                endpoint.getId(),
                endpoint.getName(),
                endpoint.getUrl(),
                endpoint.getStatus(),
                endpoint.getEvents(),
                endpoint.getCreatedAt(),
                endpoint.getCreatedBy());
    }
}
