package com.cacanode.api.tenant.controller;

import com.cacanode.api.common.exception.custom.ConflictException;
import com.cacanode.api.tenant.service.SetupService;
import com.cacanode.api.tenant.service.SetupService.SetupCommand;
import com.cacanode.api.tenant.service.SetupService.SetupResult;

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

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        if (!setupService.required()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("required", true));
    }

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
    public static class SetupRequest {
        @NotBlank(message = "Organization name is required")
        @Size(max = 255)
        private String organizationName;

        @NotBlank(message = "Your name is required")
        @Size(max = 255)
        private String fullName;

        @Email(message = "Invalid email format")
        @NotBlank(message = "Email must not be blank")
        private String email;

        @NotBlank(message = "Password must not be blank")
        @Size(min = 12, max = 128, message = "Password must be at least 12 characters")
        private String password;

        private boolean allowSelfRegistration = false;
    }

    public record SetupResponse(UUID organizationId, UUID workspaceId, UUID ownerId) {
    }
}
