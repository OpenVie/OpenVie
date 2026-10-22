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

import com.openvie.api.notification.service.CredentialCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Delivers journaled webhook events: HTTP POST with an HMAC-SHA256 signature,
 * bounded retries, and an explicit DEAD terminal status. HTTP happens outside
 * any transaction; the claim window keeps concurrent API instances apart
 * through SKIP LOCKED row reads.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "WEBHOOK-DISPATCH")
public class WebhookDeliveryDispatcher {

    private static final int BATCH = 25;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final WebhookEventPublisher eventPublisher;
    private final CredentialCipher cipher;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.webhooks.enabled:true}")
    private boolean enabled;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Scheduled(fixedDelayString = "${app.webhooks.dispatch-interval-ms:5000}")
    public void dispatchDue() {
        if (!enabled) {
            return;
        }
        Pageable page = PageRequest.of(0, BATCH);
        LocalDateTime now = LocalDateTime.now();
        var claimTemplate = new TransactionTemplate(transactionManager);
        var due = claimTemplate.execute(status -> deliveryRepository.lockDueDeliveries(now, page));
        if (due == null || due.isEmpty()) {
            return;
        }
        for (WebhookDelivery delivery : due) {
            deliverOne(delivery.getId());
        }
    }

    /** One delivery attempt, HTTP excluded from the database transaction. */
    private void deliverOne(UUID deliveryId) {
        var claimTemplate = new TransactionTemplate(transactionManager);
        WebhookDelivery delivery = claimTemplate.execute(status ->
                deliveryRepository.findById(deliveryId)
                        .filter(row -> "PENDING".equals(row.getStatus()))
                        .orElse(null));
        if (delivery == null) {
            return; // another instance took it in the claim window
        }

        WebhookEndpoint endpoint = endpointRepository.findById(delivery.getEndpointId()).orElse(null);
        if (endpoint == null || !"ACTIVE".equals(endpoint.getStatus())) {
            markTerminal(delivery.getId(), "DEAD", null, "Endpoint is disabled or missing");
            return;
        }
        if (!cipher.available() || endpoint.getSecretEncrypted() == null) {
            markTerminal(delivery.getId(), "DEAD", null, "Endpoint has no usable signing secret configured");
            return;
        }

        String body = eventPublisher.envelope(delivery);
        String secret;
        try {
            secret = cipher.decrypt(endpoint.getSecretEncrypted());
        } catch (Exception exception) {
            markTerminal(delivery.getId(), "DEAD", null, "Secret decryption failed");
            return;
        }
        int status;
        String error = null;
        try {
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.getUrl()))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "OpenVie-Webhooks/1.0")
                    .header("X-OpenVie-Event-Id", delivery.getEventId().toString())
                    .header("X-OpenVie-Event-Type", delivery.getEventType())
                    .header("X-OpenVie-Delivery-Id", delivery.getId().toString())
                    .header("X-OpenVie-Signature",
                            WebhookEventPublisher.signatureHeader(secret, timestamp, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
        } catch (Exception exception) {
            status = 0;
            error = exception.getClass().getSimpleName()
                    + (exception.getMessage() == null ? "" : ": " + exception.getMessage());
        }

        boolean delivered = status >= 200 && status < 300;
        if (delivered) {
            markTerminal(delivery.getId(), "DELIVERED", status, null);
            return;
        }
        retryOrDead(delivery.getId(), status, error);
    }

    private void retryOrDead(UUID deliveryId, int httpStatus, String error) {
        var claimTemplate = new TransactionTemplate(transactionManager);
        claimTemplate.execute(status -> {
            deliveryRepository.findById(deliveryId)
                    .filter(row -> "PENDING".equals(row.getStatus()))
                    .ifPresent(row -> {
                        int attempt = row.getAttemptCount() + 1;
                        row.setAttemptCount(attempt);
                        row.setLastHttpStatus(httpStatus == 0 ? null : httpStatus);
                        row.setLastError(error);
                        if (attempt >= WebhookDelivery.MAX_ATTEMPTS) {
                            row.setStatus("DEAD");
                            log.warn("Webhook delivery dead after {} attempts delivery={} type={}",
                                    attempt, deliveryId, row.getEventType());
                        } else {
                            row.setNextAttemptAt(LocalDateTime.now().plusSeconds(backoffSeconds(attempt)));
                        }
                        deliveryRepository.save(row);
                    });
            return null;
        });
    }

    private void markTerminal(UUID deliveryId, String terminalStatus, Integer httpStatus, String error) {
        var claimTemplate = new TransactionTemplate(transactionManager);
        claimTemplate.execute(status -> {
            deliveryRepository.findById(deliveryId).ifPresent(row -> {
                row.setStatus(terminalStatus);
                if ("DELIVERED".equals(terminalStatus)) {
                    row.setLastHttpStatus(httpStatus);
                    row.setLastError(null);
                    row.setDeliveredAt(LocalDateTime.now());
                } else {
                    row.setLastError(error);
                }
                deliveryRepository.save(row);
            });
            return null;
        });
    }

    /** Linear-friendly exponential backoff: 30 s, 2 m, 8 m, 32 m. */
    static long backoffSeconds(int attempt) {
        return (long) (30 * Math.pow(4, attempt - 1));
    }
}
