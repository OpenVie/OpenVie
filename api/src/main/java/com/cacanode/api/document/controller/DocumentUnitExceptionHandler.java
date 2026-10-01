package com.cacanode.api.document.controller;

import com.cacanode.api.ai.api.AiInferenceException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = DocumentController.class)
public class DocumentUnitExceptionHandler {
    @ExceptionHandler(AiInferenceException.class)
    public ResponseEntity<Map<String, String>> handle(AiInferenceException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(Map.of("detail", exception.getMessage()));
    }
}
