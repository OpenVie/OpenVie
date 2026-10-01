package com.cacanode.api.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AcceptInvitationRequest {
    @NotBlank
    private String token;

    @NotBlank
    @Size(max = 255)
    private String fullName;

    @NotBlank
    @Size(min = 8, max = 128)
    private String password;
}
