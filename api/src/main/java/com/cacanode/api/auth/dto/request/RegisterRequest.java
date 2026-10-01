package com.cacanode.api.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Self-registration request. Honored only while the organization enables
 * self-registration; otherwise the API answers 409.
 */
@Getter
@Setter
public class RegisterRequest {

    @Email(message = "Invalid email format")
    @NotBlank(message = "Email must not be blank")
    private String email;

    @NotBlank(message = "Full name must not be blank")
    @Size(max = 255)
    private String fullName;

    @NotBlank(message = "Password must not be blank")
    @Size(min = 12, max = 128, message = "Password must be at least 12 characters")
    private String password;
}
