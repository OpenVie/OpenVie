package com.openvie.api.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Self-service password change; also completes the forced change required after an "
        + "administrator-set initial password.")
public class ChangePasswordRequest {

    /**
     * Required whenever the caller knows a current password. When an
     * administrator set the initial password, the owner presents that initial
     * credential once to prove possession.
     */
    @Schema(description = "The caller's current password; for a first forced change, the administrator-set initial password.")
    @Size(max = 128)
    private String currentPassword;

    @Schema(description = "The new password (12–128 characters).")
    @NotBlank(message = "New password must not be blank")
    @Size(min = 12, max = 128, message = "Password must be at least 12 characters")
    private String newPassword;
}
