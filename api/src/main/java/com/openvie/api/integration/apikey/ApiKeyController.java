package com.openvie.api.integration.apikey;

import com.openvie.api.common.controller.BaseController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Owner-only lifecycle of machine-to-machine credentials. The plaintext secret
 * exists only in the create response; listings show prefixes, never secrets.
 */
@Tag(name = "Api keys", description = "Machine-to-machine credentials for integrations (owner only)")
@RestController
@RequestMapping({"/api/v1/admin/api-keys", "/api/admin/api-keys"})
@RequiredArgsConstructor
@PreAuthorize("hasRole('ORG_OWNER')")
@SecurityRequirement(name = "bearerAuth")
public class ApiKeyController extends BaseController {

    private final ApiKeyService apiKeyService;

    @Operation(summary = "Create an API key (owner)",
            description = "Provisions a service-member account and returns the Bearer secret exactly once — "
                    + "the value starts with `ovk_` and is used as `Authorization: Bearer ovk_...`.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Key created; `key` shown only once", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Validation failed: name, role, expiry, or the workspace is not active",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may manage API keys"),
            @ApiResponse(responseCode = "404", description = "Workspace not found in the organization",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ApiKeyDtos.CreatedApiKey> create(
            @Valid @RequestBody ApiKeyDtos.CreateApiKeyRequest body,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                apiKeyService.create(getOrgId(request), getUserId(request), body));
    }

    @Operation(summary = "List the organization's API keys (owner)",
            description = "Masked keys with usage timestamps and expiry; secrets are write-only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Keys", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may manage API keys")
    })
    @GetMapping
    public List<ApiKeyDtos.ApiKeyView> list(HttpServletRequest request) {
        return apiKeyService.list(getOrgId(request));
    }

    @Operation(summary = "Revoke an API key (owner)",
            description = "The key stops authenticating immediately; its service-member account is suspended.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Key revoked"),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may manage API keys"),
            @ApiResponse(responseCode = "404", description = "Key not found in the organization",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID keyId, HttpServletRequest request) {
        apiKeyService.revoke(getOrgId(request), getUserId(request), keyId);
        return ResponseEntity.noContent().build();
    }
}
