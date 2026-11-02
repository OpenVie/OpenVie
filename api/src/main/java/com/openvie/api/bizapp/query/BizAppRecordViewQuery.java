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

package com.openvie.api.bizapp.query;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes a manifest report view (already SELECT-validated by
 * {@code BizAppManifestValidator}) against the core database with only two
 * bind parameters allowed — {@code :tenant} and {@code :app} — and a hard
 * row cap. JDBC lives in this query package only, per the module
 * architecture rules.
 */
@Component
@RequiredArgsConstructor
public class BizAppRecordViewQuery {

    private static final int MAX_ROWS = 500;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> run(UUID tenantId, String appKind, String sql, Map<String, Object> params) {
        Map<String, Object> binds = new HashMap<>();
        // Bind the UUID object so PostgreSQL compares `uuid = uuid` (a string
        // would fail with `operator does not exist: uuid = character varying`).
        binds.put("tenant", tenantId);
        binds.put("app", appKind);
        if (params != null) {
            binds.putAll(params);
        }
        return jdbcTemplate.queryForList(sql, binds).stream()
                .limit(MAX_ROWS)
                .collect(java.util.stream.Collectors.toList());
    }
}
