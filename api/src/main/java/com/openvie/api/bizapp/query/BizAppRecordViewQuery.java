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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Executes a compiled report query against the core database in a read-only
 * transaction with a hard row limit.
 */
@Component
@RequiredArgsConstructor
public class BizAppRecordViewQuery {

    private static final int MAX_ROWS = 500;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> run(String sql, Map<String, Object> params) {
        return jdbcTemplate.queryForList(sql, params == null ? Map.of() : params).stream()
                .limit(MAX_ROWS)
                .collect(java.util.stream.Collectors.toList());
    }
}
