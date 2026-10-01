package com.cacanode.api.ai.api;

import org.springframework.http.HttpStatus;

public class AiInferenceException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AiInferenceException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
