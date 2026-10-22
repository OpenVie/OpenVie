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

/**
 * Self-registration request. Honored only while the organization enables
 * self-registration; otherwise the API answers 409.
 */
@Getter
@Setter
@Schema(description = "Self-registration request. Honored only while the organization enables self-registration; "
        + "otherwise the API answers 409. The account joins the default workspace and every public workspace.")
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
