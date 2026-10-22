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

package com.openvie.api.chat.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDtosValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void submitMessageRequiresContentWithinLengthLimit() {
        assertEquals(0, validator.validate(
                new ChatDtos.SubmitMessageRequest("Hello", Map.of(), null)).size());

        assertTrue(validator.validate(new ChatDtos.SubmitMessageRequest("   ", Map.of(), null))
                .stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("content")));

        assertTrue(validator.validate(new ChatDtos.SubmitMessageRequest("x".repeat(32001), Map.of(), null))
                .stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("content")));
    }

    @Test
    void submitMessageLimitsDocumentScopeSize() {
        List<UUID> oversized = java.util.stream.Stream
                .generate(UUID::randomUUID)
                .limit(21)
                .toList();

        assertTrue(validator.validate(new ChatDtos.SubmitMessageRequest("Hello", Map.of(), oversized))
                .stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("documentIds")));

        List<UUID> withinLimit = java.util.stream.Stream
                .generate(UUID::randomUUID)
                .limit(20)
                .toList();
        assertEquals(0, validator.validate(
                new ChatDtos.SubmitMessageRequest("Hello", Map.of(), withinLimit)).size());
    }

    @Test
    void createSessionLimitsLocaleLength() {
        assertEquals(0, validator.validate(
                new ChatDtos.CreateSessionRequest(null, null, "vi-VN", Map.of())).size());

        assertTrue(validator.validate(
                        new ChatDtos.CreateSessionRequest(null, null, "x".repeat(21), Map.of()))
                .stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("locale")));
    }
}
