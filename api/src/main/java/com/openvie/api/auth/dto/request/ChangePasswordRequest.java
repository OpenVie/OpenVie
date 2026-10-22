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
