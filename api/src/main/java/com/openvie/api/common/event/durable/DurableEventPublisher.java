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

package com.openvie.api.common.event.durable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DurableEventPublisher {
    private final ModuleEventOutboxRepository repository;
    private final ObjectMapper objectMapper;

    public UUID publish(String stableType, int version, Object payload) {
        Map<String, Object> serializedPayload;
        try {
            serializedPayload = objectMapper.convertValue(payload, new TypeReference<>() { });
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Module event payload cannot be serialized", exception);
        }
        LocalDateTime now = LocalDateTime.now();
        ModuleEventOutbox event = new ModuleEventOutbox();
        event.setEventId(UUID.randomUUID());
        event.setEventType(stableType);
        event.setEventVersion(version);
        event.setPayload(serializedPayload);
        event.setCreatedAt(now);
        event.setStatus(ModuleEventStatus.PENDING);
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        repository.save(event);
        return event.getEventId();
    }
}
