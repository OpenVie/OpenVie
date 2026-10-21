package com.openvie.api.integration.apikey;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.UUID;

public final class ApiKeyDtos {
    private ApiKeyDtos() {
    }

    @Schema(description = "Create a machine-to-machine credential for one workspace.")
    public record CreateApiKeyRequest(
            @Schema(description = "Display name, shown in listings and in the service-member profile.", example = "Marketing site search")
            @NotBlank @Size(max = 120) String name,
            @Schema(description = "Workspace the key operates in.")
            @NotNull UUID workspaceId,
            @Schema(description = "Workspace authority of the key: WORKSPACE_ADMIN or MEMBER.", example = "MEMBER")
            @Pattern(regexp = "WORKSPACE_ADMIN|MEMBER", message = "role must be WORKSPACE_ADMIN or MEMBER") String role,
            @Schema(description = "Optional expiry; after this instant the key answers 401.")
            LocalDateTime expiresAt
    ) {
    }

    @Schema(description = "Creation response: `key` is the secret shown exactly once — store it now.")
    public record CreatedApiKey(
            UUID id,
            @Schema(description = "Bearer secret in the form `ovk_<secret>`.", example = "ovk_7Yq3pRv9xLd2KmBaWf8hTc0nZj5GaQeU")
            String key,
            ApiKeyView keyView
    ) {
    }

    @Schema(description = "One API key, masked. Secrets are write-only.")
    public record ApiKeyView(
            UUID id,
            UUID workspaceId,
            UUID serviceUserId,
            String name,
            @Schema(description = "`ovk_` + first secret characters, for identification only.")
            String keyPrefix,
            String role,
            String status,
            LocalDateTime createdAt,
            LocalDateTime lastUsedAt,
            LocalDateTime expiresAt,
            UUID createdBy,
            @Schema(description = "True when the key can no longer authenticate (revoked or expired).")
            boolean expired
    ) {
    }
}
