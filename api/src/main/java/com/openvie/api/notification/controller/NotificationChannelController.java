package com.openvie.api.notification.controller;

import com.openvie.api.common.controller.BaseController;
import com.openvie.api.notification.service.NotificationChannelService;
import com.openvie.api.notification.service.NotificationChannelService.ChannelRequest;
import com.openvie.api.notification.service.NotificationChannelService.ChannelView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Organization-owner notification channel administration. Credential values
 * are write-only: reads return presence, never the stored secret.
 *
 * <p>Soft admin boundary (Keycloak-ready): the {@code /api/v1/admin/channels}
 * alias exposes the same handler under the admin prefix so the gateway can
 * rate-limit and authorize it independently. The canonical path stays until
 * the hard split; the frontend admin surface already calls the alias.
 */
@Tag(name = "Notification channels", description = "Organization mail plugins (owner only)")
@RestController
@RequestMapping({"/api/v1/channels", "/api/v1/admin/channels"})
@RequiredArgsConstructor
@PreAuthorize("hasRole('ORG_OWNER')")
@SecurityRequirement(name = "bearerAuth")
public class NotificationChannelController extends BaseController {

    private final NotificationChannelService channelService;

    public record TestRequest(@NotBlank @Email String email) {
    }

    @Operation(summary = "List the organization's notification channels",
            description = "Configured mail plugins with credential presence flags — stored secrets are write-only and "
                    + "never echoed back.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Channels"),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may manage channels")
    })
    @GetMapping
    public List<ChannelView> list(HttpServletRequest request) {
        return channelService.list(getOrgId(request));
    }

    @Operation(summary = "Create or update a notification channel",
            description = "Upserts the mail plugin of `type` (unknown types answer 400). Null credential fields keep "
                    + "the stored values; the response never contains secrets.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Channel saved", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Unknown channel type",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Stored channels require NOTIFICATION_ENC_KEY to be configured",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PutMapping("/{type}")
    public ChannelView upsert(
            @PathVariable String type,
            @Valid @RequestBody ChannelRequest body,
            HttpServletRequest request) {
        return channelService.upsert(getOrgId(request), new ChannelRequest(
                type, body.enabled(), body.fromEmail(), body.fromName(), body.host(),
                body.port(), body.username(), body.password(), body.auth(),
                body.starttls()));
    }

    @Operation(summary = "Enable or disable a channel")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New state applied", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "The channel type has no registered transport, or credentials are incomplete",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Channel not found in the organization",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PostMapping("/{channelId}/enabled")
    public ChannelView setEnabled(
            @PathVariable UUID channelId,
            @RequestBody Map<String, Boolean> body,
            HttpServletRequest request) {
        return channelService.setEnabled(getOrgId(request), channelId,
                Boolean.TRUE.equals(body.get("enabled")));
    }

    @Operation(summary = "Delete a notification channel")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Channel deleted"),
            @ApiResponse(responseCode = "404", description = "Channel not found in the organization",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @DeleteMapping("/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID channelId, HttpServletRequest request) {
        channelService.delete(getOrgId(request), channelId);
    }

    @Operation(summary = "Send a test email through the active channel",
            description = "Delivers a test message to the given address using the organization's active notification channel.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Test message sent"),
            @ApiResponse(responseCode = "400", description = "Email validation failed",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "No active channel, or the selected provider is not usable with the current settings",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PostMapping("/test")
    public ResponseEntity<Void> test(
            @Valid @RequestBody TestRequest body,
            HttpServletRequest request) {
        channelService.testDelivery(getOrgId(request), body.email());
        return ResponseEntity.noContent().build();
    }
}
