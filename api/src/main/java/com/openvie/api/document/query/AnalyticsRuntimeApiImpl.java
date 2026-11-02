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

package com.openvie.api.document.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.document.api.AnalyticsRuntimeApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Published implementation of {@link AnalyticsRuntimeApi} residing in the query package.
 */
@Service
@Slf4j(topic = "ANALYTICS-API")
public class AnalyticsRuntimeApiImpl implements AnalyticsRuntimeApi {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AnalyticsRuntimeApiImpl(
            @Value("${app.lakehouse.url:http://localhost:18000}") String lakehouseUrl,
            ObjectMapper objectMapper,
            NamedParameterJdbcTemplate jdbcTemplate) {
        this.restClient = RestClient.builder().baseUrl(lakehouseUrl).build();
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Map<String, Object>> queryLakehouse(
            UUID tenantId, String dataset, String duckDbSql, Map<String, Object> params) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("tenant_id", tenantId.toString());
            payload.put("query", duckDbSql);
            if (params != null && params.containsKey("limit")) {
                payload.put("limit", params.get("limit"));
            }

            String json = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/lakehouse/query")
                            .queryParam("dataset", dataset)
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            if (json != null && !json.isBlank()) {
                Map<String, Object> resp = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                if (resp.get("columns") instanceof List<?> cols && resp.get("rows") instanceof List<?> rawRows) {
                    List<Map<String, Object>> results = new ArrayList<>();
                    for (Object rowObj : rawRows) {
                        if (rowObj instanceof List<?> rowValues) {
                            Map<String, Object> rowMap = new HashMap<>();
                            for (int i = 0; i < cols.size() && i < rowValues.size(); i++) {
                                rowMap.put(String.valueOf(cols.get(i)), rowValues.get(i));
                            }
                            results.add(rowMap);
                        }
                    }
                    return results;
                }
            }
        } catch (Exception ex) {
            log.debug("Remote lakehouse query unavailable, falling back to operational store: {}", ex.getMessage());
        }

        // Consistent fallback to operational PostgreSQL database
        return jdbcTemplate.queryForList(duckDbSql, params == null ? Map.of() : params);
    }
}
