package com.cacanode.api.auth.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ResendVerificationResponse {
    private String message;
    private Integer canRetryAfterSeconds;
}
