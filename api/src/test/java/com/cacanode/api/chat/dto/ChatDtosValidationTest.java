package com.cacanode.api.chat.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDtosValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void submitMessageRequiresContentWithinLengthLimit() {
        assertEquals(0, validator.validate(
                new ChatDtos.SubmitMessageRequest("Hello", Map.of())).size());

        assertTrue(validator.validate(new ChatDtos.SubmitMessageRequest("   ", Map.of()))
                .stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("content")));

        assertTrue(validator.validate(new ChatDtos.SubmitMessageRequest("x".repeat(32001), Map.of()))
                .stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("content")));
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
