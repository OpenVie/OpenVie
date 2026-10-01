package com.cacanode.api.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VerifyLogin2FARequest {
    @NotBlank(message = "Token is required")
    private String token;
}
