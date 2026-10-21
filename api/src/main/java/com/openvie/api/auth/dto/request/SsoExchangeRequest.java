package com.openvie.api.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * PKCE authorization code handed to the backend for a
 * server-side exchange (code + verifier never leave this call; the browser
 * never sees any client secret).
 */
@Getter
@Setter
@Schema(description = "One-time PKCE authorization code handed to the backend for the server-side Keycloak exchange "
        + "(code and verifier never leave this call; the browser never sees any client secret).")
public class SsoExchangeRequest {

    @NotBlank(message = "Code must not be blank")
    private String code;

    @NotBlank(message = "Code verifier must not be blank")
    private String codeVerifier;

    @NotBlank(message = "Redirect URI must not be blank")
    private String redirectUri;
}
