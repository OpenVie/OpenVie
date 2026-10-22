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

import com.openvie.api.integration.IntegrationCredentials;
import com.openvie.api.tenant.model.Tenant;
import com.openvie.api.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records integration events into the webhook delivery outbox. Called inside
 * the transaction of the domain change (transactional outbox discipline): if
 * the domain write rolls back, no event is journaled; if it commits, delivery
 * becomes eventual with bounded retries.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "WEBHOOK-EVENT")
public class WebhookEventPublisher {

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final TenantRepository workspaceRepository;
    private final ObjectMapper objectMapper;

    /**
     * Journales one workspace-scoped event for every active endpoint of its
     * organization. Failures degrade to a log line: event delivery is
     * best-effort and must never break the producer's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void publish(UUID workspaceId, String eventType, UUID sourceEventId, Map<String, Object> data) {
        try {
            publishStrict(workspaceId, eventType, sourceEventId, data);
        } catch (Exception exception) {
            log.warn("Webhook outbox write failed for {}: {}", eventType, exception.getMessage());
        }
    }

    private void publishStrict(UUID workspaceId, String eventType, UUID sourceEventId, Map<String, Object> data) {
        UUID orgId = workspaceId == null ? null : workspaceRepository.findById(workspaceId)
                .map(Tenant::getOrganization)
                .map(org -> org.getId())
                .orElse(null);
        if (orgId == null) {
            return;
        }
        List<WebhookEndpoint> endpoints = endpointRepository
                .findByOrgIdAndStatusAndSecretEncryptedIsNotNull(orgId, "ACTIVE").stream()
                .filter(endpoint -> endpoint.acceptsEventType(eventType))
                .toList();
        if (endpoints.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (WebhookEndpoint endpoint : endpoints) {
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setEventId(sourceEventId);
            delivery.setEndpointId(endpoint.getId());
            delivery.setOrgId(orgId);
            delivery.setTenantId(workspaceId);
            delivery.setEventType(eventType);
            delivery.setPayload(data == null ? Map.of() : data);
            delivery.setAttemptCount(0);
            delivery.setNextAttemptAt(now);
            deliveryRepository.save(delivery);
        }
        log.info("Webhook event {} scheduled for {} endpoint(s)", eventType, endpoints.size());
    }

    /**
     * The JSON body POSTed to receivers: a stable envelope around the stored
     * event data. `id` is the event id (shared across endpoints for one event)
     * so receivers can dedupe replays.
     */
    public String envelope(WebhookDelivery delivery) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", delivery.getEventId().toString());
        envelope.put("type", delivery.getEventType());
        envelope.put("occurred_at", delivery.getCreatedAt() == null
                ? LocalDateTime.now().toString()
                : delivery.getCreatedAt().toString());
        envelope.put("workspace_id", delivery.getTenantId() == null
                ? null
                : delivery.getTenantId().toString());
        envelope.put("data", delivery.getPayload());
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception exception) {
            throw new IllegalStateException("Webhook payload serialization failed", exception);
        }
    }

    /** Signature header value: `t=<unix seconds>,v1=<hex hmac>.` */
    public static String signatureHeader(String secret, String unixTimestamp, String body) {
        return "t=" + unixTimestamp + ",v1=" + IntegrationCredentials.hmacSha256Hex(secret, unixTimestamp + "." + body);
    }
}
