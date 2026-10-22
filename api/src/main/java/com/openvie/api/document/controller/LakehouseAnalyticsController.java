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

package com.openvie.api.document.controller;

import com.openvie.api.common.controller.BaseController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Exposes the cold Lakehouse OLAP analytical engine (DuckDB over Parquet on SeaweedFS).
 *
 * <p>Separates hot transactional queries from analytical aggregate reporting,
 * adhering to DX-OS principle (i) without placing load on the operational PostgreSQL database.
 */
@Tag(name = "Lakehouse Analytics", description = "DuckDB-powered Lakehouse OLAP queries and aggregate analytics")
@RestController
@RequestMapping({"/api/v1/analytics/lakehouse", "/api/analytics/lakehouse"})
@Slf4j(topic = "LAKEHOUSE-CONTROLLER")
public class LakehouseAnalyticsController extends BaseController {

    private final RestClient restClient;
    private final String lakehouseUrl;

    public LakehouseAnalyticsController(
            @Value("${app.lakehouse.url:http://localhost:18000}") String lakehouseUrl) {
        this.lakehouseUrl = lakehouseUrl;
        this.restClient = RestClient.builder().baseUrl(lakehouseUrl).build();
    }

    @Operation(summary = "Check Lakehouse and DuckDB engine health")
    @GetMapping("/health")
    public ResponseEntity<String> getHealth() {
        try {
            String response = restClient.get()
                    .uri("/api/v1/lakehouse/health")
                    .retrieve()
                    .body(String.class);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.warn("Lakehouse service health check failed at {}: {}", lakehouseUrl, e.getMessage());
            Map<String, String> fallback = new HashMap<>();
            fallback.put("status", "unavailable");
            fallback.put("detail", e.getMessage());
            return ResponseEntity.status(503).body("{\"status\":\"unavailable\",\"engine\":\"DuckDB\"}");
        }
    }

    @Operation(summary = "Execute analytical read-only SQL query over Lakehouse Parquet partitions")
    @PostMapping(value = "/query", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> executeQuery(
            HttpServletRequest request,
            @RequestParam(defaultValue = "chat_analytics") String dataset,
            @RequestBody Map<String, Object> body) {
        UUID tenantId = getTenantId(request);
        Map<String, Object> payload = new HashMap<>(body);
        payload.put("tenant_id", tenantId.toString());

        String response = restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/lakehouse/query")
                        .queryParam("dataset", dataset)
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(String.class);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get aggregated chat interaction analytics")
    @GetMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getChatAnalytics(HttpServletRequest request) {
        UUID tenantId = getTenantId(request);
        String response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/lakehouse/analytics/chat")
                        .queryParam("tenant_id", tenantId)
                        .build())
                .retrieve()
                .body(String.class);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get aggregated document ingestion and storage metrics")
    @GetMapping(value = "/documents", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getDocumentAnalytics(HttpServletRequest request) {
        UUID tenantId = getTenantId(request);
        String response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/lakehouse/analytics/documents")
                        .queryParam("tenant_id", tenantId)
                        .build())
                .retrieve()
                .body(String.class);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get aggregated security and audit event metrics")
    @GetMapping(value = "/audit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getAuditAnalytics(HttpServletRequest request) {
        UUID tenantId = getTenantId(request);
        String response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/lakehouse/analytics/audit")
                        .queryParam("tenant_id", tenantId)
                        .build())
                .retrieve()
                .body(String.class);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get aggregated document approval workflow and SLA metrics")
    @GetMapping(value = "/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getWorkflowAnalytics(HttpServletRequest request) {
        UUID tenantId = getTenantId(request);
        String response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/lakehouse/analytics/workflows")
                        .queryParam("tenant_id", tenantId)
                        .build())
                .retrieve()
                .body(String.class);
        return ResponseEntity.ok(response);
    }
}
