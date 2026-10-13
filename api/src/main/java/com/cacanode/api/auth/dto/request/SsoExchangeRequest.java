package com.cacanode.api.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * PKCE authorization code handed to the backend for a
 * server-side exchange (code + verifier never leave this call; the browser
 * never sees any client secret).
 */
@Getter
@Setter
public class SsoExchangeRequest {

    @NotBlank(message = "Code must not be blank")
    private String code;

    @NotBlank(message = "Code verifier must not be blank")
    private String codeVerifier;

    @NotBlank(message = "Redirect URI must not be blank")
    private String redirectUri;
}
