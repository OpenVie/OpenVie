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

package com.openvie.api.integration.webhook;

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
 * Owner-only webhook endpoint management. Delivery of the events themselves is
 * asynchronous: this controller only registers targets and reads the outbox.
 */
@Tag(name = "Webhooks", description = "Signed event push to your systems: register endpoints, inspect deliveries (owner only)")
@RestController
@RequestMapping("/api/v1/admin/webhook-endpoints")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ORG_OWNER')")
@SecurityRequirement(name = "bearerAuth")
public class WebhookController extends BaseController {

    private final WebhookService webhookService;

    @Operation(summary = "Create a webhook endpoint (owner)",
            description = "Registers a POST target and returns its signing secret exactly once. Deliveries carry the "
                    + "event JSON body signed with `X-OpenVie-Signature: t=<unix>,v1=<HMAC-SHA256 of "
                    + "`<timestamp>.<body>`, secret>`; replay-protect with `X-OpenVie-Event-Id`.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Endpoint created; `secret` shown only once", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Validation failed: name, URL, or event filter",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may manage webhook endpoints"),
            @ApiResponse(responseCode = "409", description = "Signing secrets require app.notifications.encryption-key",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<WebhookDtos.CreatedWebhook> create(
            @Valid @RequestBody WebhookDtos.CreateWebhookRequest body,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(webhookService.create(
                getOrgId(request), getTenantId(request), getUserId(request), body));
    }

    @Operation(summary = "List the organization's webhook endpoints (owner)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Endpoints", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may manage webhook endpoints")
    })
    @GetMapping
    public List<WebhookDtos.WebhookEndpointView> list(HttpServletRequest request) {
        return webhookService.list(getOrgId(request));
    }

    @Operation(summary = "Delete a webhook endpoint (owner)",
            description = "Stops delivery immediately; the endpoint's pending outbox rows are discarded by cascade.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Endpoint deleted"),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may manage webhook endpoints"),
            @ApiResponse(responseCode = "404", description = "Endpoint not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @DeleteMapping("/{endpointId}")
    public ResponseEntity<Void> delete(@PathVariable UUID endpointId, HttpServletRequest request) {
        webhookService.delete(getOrgId(request), getTenantId(request), getUserId(request), endpointId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Send a test delivery (owner)",
            description = "Journals a `webhook.test` event for this endpoint; the dispatcher delivers it like any "
                    + "other event, so signature verification can be tested end to end.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Test delivery scheduled"),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may manage webhook endpoints"),
            @ApiResponse(responseCode = "404", description = "Endpoint not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Endpoint is disabled",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PostMapping("/{endpointId}/test")
    public ResponseEntity<Void> test(@PathVariable UUID endpointId, HttpServletRequest request) {
        webhookService.testSend(getOrgId(request), getUserId(request), endpointId);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Delivery log of one endpoint (owner)",
            description = "The 20 most recent outbox rows: status (PENDING / DELIVERED / DEAD), attempt counts, "
                    + "last HTTP status, and errors — plus the event id used for receiver-side dedupe.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deliveries", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may manage webhook endpoints"),
            @ApiResponse(responseCode = "404", description = "Endpoint not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @GetMapping("/{endpointId}/deliveries")
    public List<WebhookDtos.WebhookDeliveryView> deliveries(
            @PathVariable UUID endpointId, HttpServletRequest request) {
        return webhookService.deliveries(getOrgId(request), endpointId);
    }
}
