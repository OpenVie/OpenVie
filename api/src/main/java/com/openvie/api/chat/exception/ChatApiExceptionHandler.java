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

package com.openvie.api.chat.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.openvie.api.common.exception.custom.UnauthorizedException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import com.openvie.api.ai.api.AiInferenceException;

import java.util.Map;
import java.util.UUID;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.openvie.api.chat")
public class ChatApiExceptionHandler {
    @ExceptionHandler(ChatApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ChatApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus()).body(envelope(
                exception.getCode(), exception.getMessage(), request));
    }

    @ExceptionHandler(AiInferenceException.class)
    public ResponseEntity<Map<String, Object>> handleAi(AiInferenceException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus()).body(envelope(
                exception.getCode(), exception.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst().map(error -> error.getDefaultMessage()).orElse("Request is invalid.");
        return ResponseEntity.badRequest().body(envelope("VALIDATION_ERROR", message, request));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(
            UnauthorizedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(envelope("UNAUTHORIZED", exception.getMessage(), request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(
            AccessDeniedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(envelope("FORBIDDEN", exception.getMessage(), request));
    }

    private Map<String, Object> envelope(String code, String message, HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-ID");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return Map.of("error", Map.of("code", code, "message", message, "request_id", requestId));
    }
}
