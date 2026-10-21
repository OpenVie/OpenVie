package com.openvie.api.integration.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class WebhookDtos {
    private WebhookDtos() {
    }

    @Schema(description = "Register a webhook endpoint that receives signed events.")
    public record CreateWebhookRequest(
            @Schema(description = "Display name for listings.", example = "DMS bridge")
            @NotBlank @Size(max = 120) String name,
            @Schema(description = "POST target; must be http(s).", example = "https://dms.example.com/openvie/hooks")
            @NotBlank @Size(max = 1000)
            @Pattern(regexp = "^https?://\\S+$", message = "url must be an http(s) URL") String url,
            @Schema(description = "Optional event whitelist; empty or absent receives every event. "
                    + "Known types: document.status.changed, workflow.started, workflow.approved, "
                    + "workflow.rejected, workflow.escalated, webhook.test.")
            List<String> events
    ) {
    }

    @Schema(description = "Creation response: `secret` is the signing key shown exactly once — store it now; "
            + "webhook deliveries signed with it carry `X-OpenVie-Signature: t=...,v1=...`.")
    public record CreatedWebhook(
            UUID id,
            @Schema(description = "Signing secret in the form `whsec_<secret>`.", example = "whsec_7Yq3pRv9xLd2KmBaWf8hTc0nZj5GaQeU")
            String secret,
            WebhookEndpointView endpoint
    ) {
    }

    @Schema(description = "One webhook endpoint. Secrets are write-only.")
    public record WebhookEndpointView(
            UUID id,
            String name,
            String url,
            String status,
            List<String> events,
            LocalDateTime createdAt,
            UUID createdBy
    ) {
    }

    @Schema(description = "One delivery attempt record of the outbox.")
    public record WebhookDeliveryView(
            UUID id,
            UUID eventId,
            @JsonProperty("event_type") String eventType,
            String status,
            int attemptCount,
            @JsonProperty("last_http_status") Integer lastHttpStatus,
            String lastError,
            LocalDateTime createdAt,
            LocalDateTime deliveredAt,
            @JsonProperty("next_attempt_at")
            LocalDateTime nextAttemptAt
    ) {
    }
}
