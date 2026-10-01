package com.cacanode.api.chat.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.cacanode.api.common.exception.custom.UnauthorizedException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import com.cacanode.api.ai.api.AiInferenceException;

import java.util.Map;
import java.util.UUID;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.cacanode.api.chat")
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
