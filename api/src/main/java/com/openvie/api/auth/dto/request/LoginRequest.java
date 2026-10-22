// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Password login with the organization account email.")
public class LoginRequest {
  @Email(message = "Invalid email format")
  @NotBlank(message = "Email must not be blank")
  private String email;

  @NotBlank(message = "Password must not be blank")
  @Size(min = 8, message = "Password must be at least 8 characters")
  private String password;

  /** When true, refresh_token cookie lasts {@code jwt.expiry-days}; otherwise session cookie. */
  @Schema(description = "When true the refresh cookie lasts `jwt.expiry-days`; otherwise it is a session cookie.")
  private boolean rememberMe = false;
}
