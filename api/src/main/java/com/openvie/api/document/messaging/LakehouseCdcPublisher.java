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

package com.openvie.api.document.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes Change Data Capture (CDC) events to the Lakehouse streaming topic.
 *
 * <p>Separates hot transactional state from cold lakehouse analytical ingestion,
 * allowing historical analytics to run on Parquet/DuckDB without loading PostgreSQL.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "LAKEHOUSE-CDC")
public class LakehouseCdcPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Async
    public void publishCdcEvent(UUID tenantId, String dataset, Map<String, Object> data) {
        if (tenantId == null || dataset == null || data == null) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("event_id", UUID.randomUUID().toString());
            payload.put("tenant_id", tenantId.toString());
            payload.put("dataset", dataset);
            payload.put("op", "INSERT");
            payload.put("timestamp", Instant.now().toString());
            payload.put("data", data);

            byte[] body = objectMapper.writeValueAsBytes(payload);
            var message = MessageBuilder.withBody(body)
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding(StandardCharsets.UTF_8.name())
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setMessageId(String.valueOf(payload.get("event_id")))
                    .build();

            String routingKey = RabbitMqTopology.CDC_EVENT_PREFIX + dataset;
            rabbitTemplate.send(RabbitMqTopology.INGESTION_EXCHANGE, routingKey, message);
            log.debug("Published Lakehouse CDC event tenant={} dataset={}", tenantId, dataset);
        } catch (Exception e) {
            log.warn("Failed to publish CDC event to lakehouse: dataset={} tenant={} — {}",
                    dataset, tenantId, e.getMessage());
        }
    }
}
