package com.openvie.api.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Creates the member account from an invitation token and issues the credential pair.")
public class AcceptInvitationRequest {
    @Schema(description = "Invitation token from the invite email link.")
    @NotBlank
    private String token;

    @NotBlank
    @Size(max = 255)
    private String fullName;

    @Schema(description = "Initial password (8–128 characters); not forced to change later.")
    @NotBlank
    @Size(min = 8, max = 128)
    private String password;
}
