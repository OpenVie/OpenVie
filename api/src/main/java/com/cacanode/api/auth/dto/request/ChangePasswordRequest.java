package com.cacanode.api.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePasswordRequest {

    /**
     * Required whenever the caller knows a current password. When an
     * administrator set the initial password, the owner presents that initial
     * credential once to prove possession.
     */
    @Size(max = 128)
    private String currentPassword;

    @NotBlank(message = "New password must not be blank")
    @Size(min = 12, max = 128, message = "Password must be at least 12 characters")
    private String newPassword;
}
