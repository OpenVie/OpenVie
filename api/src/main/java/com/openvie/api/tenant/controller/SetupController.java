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

package com.openvie.api.tenant.controller;

import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.tenant.service.SetupService;
import com.openvie.api.tenant.service.SetupService.SetupCommand;
import com.openvie.api.tenant.service.SetupService.SetupResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * The one-time installation setup form.
 *
 * <p>Public only while the installation has no accounts. Once an account exists
 * every route here answers 404, so a running installation can never be
 * re-claimed; the web client discovers the form through
 * {@code GET /api/v1/auth/registration-status}.
 */
@Tag(name = "Setup", description = "One-time installation claim")
@RestController
@RequestMapping("/api/v1/setup")
@RequiredArgsConstructor
public class SetupController {

    private final SetupService setupService;

    @Operation(summary = "Check whether setup is required",
            description = "Signals the web client to show the one-time setup form; answers 404 once the installation "
                    + "has any account. Public endpoint.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Setup required (`{\"required\": true}`)"),
            @ApiResponse(responseCode = "404", description = "Installation already claimed")
    })
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        if (!setupService.required()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("required", true));
    }

    @Operation(summary = "Claim the installation",
            description = "Creates the organization, its default workspace, and the owner account from the setup form. "
                    + "Fails once an account exists. Public endpoint.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Installation claimed", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "This installation has already been set up",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<SetupResponse> complete(@Valid @RequestBody SetupRequest request) {
        if (!setupService.required()) {
            throw new ConflictException("This installation has already been set up");
        }
        SetupResult result = setupService.complete(new SetupCommand(
                request.getOrganizationName(), request.getFullName(), request.getEmail(),
                request.getPassword(), request.isAllowSelfRegistration()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new SetupResponse(
                result.organizationId(), result.workspaceId(), result.ownerId()));
    }

    @Getter
    @Setter
    @Schema(description = "One-time setup form: claims the installation with the organization, its default workspace, "
            + "and the owner account.")
    public static class SetupRequest {
        @Schema(description = "Organization display name.", example = "OpenVie")
        @NotBlank(message = "Organization name is required")
        @Size(max = 255)
        private String organizationName;

        @Schema(description = "Owner's display name.")
        @NotBlank(message = "Your name is required")
        @Size(max = 255)
        private String fullName;

        @Schema(description = "Owner account email; used as the login name.", example = "owner@example.com")
        @Email(message = "Invalid email format")
        @NotBlank(message = "Email must not be blank")
        private String email;

        @Schema(description = "Initial owner password (12–128 characters).")
        @NotBlank(message = "Password must not be blank")
        @Size(min = 12, max = 128, message = "Password must be at least 12 characters")
        private String password;

        @Schema(description = "When true, `/api/v1/auth/register` accepts self-registrations from the start.")
        private boolean allowSelfRegistration = false;
    }

    @Schema(description = "Ids created by the setup claim.")
    public record SetupResponse(UUID organizationId, UUID workspaceId, UUID ownerId) {
    }
}
