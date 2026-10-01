package com.cacanode.api.chat.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ChatApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ChatApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
