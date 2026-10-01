package com.cacanode.api.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {
  @Email(message = "Invalid email format")
  @NotBlank(message = "Email must not be blank")
  private String email;

  @NotBlank(message = "Password must not be blank")
  @Size(min = 8, message = "Password must be at least 8 characters")
  private String password;

  /** When true, refresh_token cookie lasts {@code jwt.expiry-days}; otherwise session cookie. */
  private boolean rememberMe = false;
}
