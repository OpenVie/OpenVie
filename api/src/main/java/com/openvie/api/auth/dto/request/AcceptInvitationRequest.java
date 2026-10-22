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
