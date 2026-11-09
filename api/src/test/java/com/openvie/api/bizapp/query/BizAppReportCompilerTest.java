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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Localized;
import com.openvie.api.common.exception.custom.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

class BizAppReportCompilerTest {

    private final BizAppReportCompiler compiler = new BizAppReportCompiler();

    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    private Entity sampleEntity() {
        return new Entity(
                "leads",
                new Localized("Khách hàng", "Leads"),
                List.of(
                        new Field("name", "string", true, 200, null, null),
                        new Field("stage", "enum", true, null, null, List.of("NEW", "WON")),
                        new Field("dealValue", "number", false, null, null, null),
                        new Field("notes", "string", false, 500, null, null)
                ),
                Map.of()
        );
    }

    @Test
    void compilesDeclarativePostgresQueryForJsonbStore() {
        Map<String, Object> viewSpec = Map.of(
                "name", "pipeline",
                "entity", "leads",
                "groupBy", List.of("stage"),
                "metrics", List.of(
                        Map.of("fn", "count", "as", "lead_count"),
                        Map.of("fn", "sum", "field", "dealValue", "as", "total_val")
                ),
                "filters", List.of(
                        Map.of("field", "stage", "op", "in", "values", List.of("NEW", "WON"))
                ),
                "orderBy", List.of(
                        Map.of("by", "lead_count", "dir", "desc")
                ),
                "limit", 50
        );

        var compiled = compiler.compile(
                BizAppReportCompiler.Dialect.POSTGRES,
                orgId,
                tenantId,
                "crm",
                sampleEntity(),
                viewSpec,
                Map.of(),
                false,
                null
        );

        String sql = compiled.sql();
        assertTrue(sql.startsWith("SELECT (payload ->> 'stage') AS stage, COUNT(*) AS lead_count, SUM((payload ->> 'dealValue')::numeric) AS total_val FROM bizapp_records WHERE "));
        assertTrue(sql.contains("org_id = :orgId AND tenant_id = :tenantId AND app_kind = :appKind AND entity = :entityName AND status = 'ACTIVE'"));
        assertTrue(sql.contains("GROUP BY (payload ->> 'stage')"));
        assertTrue(sql.contains("ORDER BY lead_count DESC"));
        assertTrue(sql.contains("LIMIT :limit"));

        assertEquals(orgId, compiled.params().get("orgId"));
        assertEquals(tenantId, compiled.params().get("tenantId"));
        assertEquals("crm", compiled.params().get("appKind"));
        assertEquals("leads", compiled.params().get("entityName"));
        assertEquals(50, compiled.params().get("limit"));
        assertEquals(List.of("NEW", "WON"), compiled.params().get("f_0_stage"));
    }

    @Test
    void compilesDuckDbDialectWithTryCast() {
        Map<String, Object> viewSpec = Map.of(
                "name", "pipeline_lakehouse",
                "entity", "leads",
                "metrics", List.of(
                        Map.of("fn", "avg", "field", "dealValue", "as", "avg_val")
                )
        );

        var compiled = compiler.compile(
                BizAppReportCompiler.Dialect.DUCKDB,
                orgId,
                tenantId,
                "crm",
                sampleEntity(),
                viewSpec,
                Map.of(),
                false,
                null
        );

        assertTrue(compiled.sql().contains("AVG(TRY_CAST(payload ->> 'dealValue' AS DOUBLE)) AS avg_val"));
    }

    @Test
    void compilesTypedTableStorage() {
        Map<String, Object> viewSpec = Map.of(
                "name", "pipeline_typed",
                "entity", "leads",
                "groupBy", List.of("stage"),
                "metrics", List.of(
                        Map.of("fn", "count", "as", "cnt"),
                        Map.of("fn", "sum", "field", "dealValue", "as", "total_val")
                )
        );

        var compiled = compiler.compile(
                BizAppReportCompiler.Dialect.POSTGRES,
                orgId,
                tenantId,
                "crm",
                sampleEntity(),
                viewSpec,
                Map.of(),
                true,
                "bizapp_crm_leads"
        );

        String sql = compiled.sql();
        assertTrue(sql.contains("FROM bizapp_crm_leads WHERE org_id = :orgId AND tenant_id = :tenantId AND status = 'ACTIVE'"));
        assertTrue(sql.contains("stage AS stage"));
        assertTrue(sql.contains("SUM(deal_value) AS total_val"));
        assertFalse(sql.contains("payload"));
    }

    @Test
    void selectsOnlyTheChosenColumnsForAListView() {
        Map<String, Object> viewSpec = Map.of(
                "name", "names_only",
                "entity", "leads",
                "fields", List.of("name", "stage"),
                "limit", 20
        );

        var compiled = compiler.compile(
                BizAppReportCompiler.Dialect.POSTGRES,
                orgId,
                tenantId,
                "crm",
                sampleEntity(),
                viewSpec,
                Map.of(),
                false,
                null
        );

        String sql = compiled.sql();
        assertTrue(sql.startsWith("SELECT (payload ->> 'name') AS name, (payload ->> 'stage') AS stage, created_at, updated_at FROM "));
        assertFalse(sql.contains("dealValue"));
    }

    @Test
    void rejectsUndeclaredReportColumn() {
        Map<String, Object> viewSpec = Map.of(
                "name", "bad_columns",
                "entity", "leads",
                "fields", List.of("name", "unknown_secret_column")
        );

        assertThrows(BadRequestException.class, () -> compiler.compile(
                BizAppReportCompiler.Dialect.POSTGRES,
                orgId,
                tenantId,
                "crm",
                sampleEntity(),
                viewSpec,
                Map.of(),
                false,
                null
        ));
    }

    @Test
    void rejectsUndeclaredFieldInFilters() {
        Map<String, Object> viewSpec = Map.of(
                "name", "invalid_filter",
                "entity", "leads",
                "filters", List.of(
                        Map.of("field", "unknown_secret_column", "op", "eq", "value", "abc")
                )
        );

        assertThrows(BadRequestException.class, () -> compiler.compile(
                BizAppReportCompiler.Dialect.POSTGRES,
                orgId,
                tenantId,
                "crm",
                sampleEntity(),
                viewSpec,
                Map.of(),
                false,
                null
        ));
    }

    @Test
    void rejectsSumOnNonNumericField() {
        Map<String, Object> viewSpec = Map.of(
                "name", "invalid_metric",
                "entity", "leads",
                "metrics", List.of(
                        Map.of("fn", "sum", "field", "notes", "as", "sum_notes")
                )
        );

        assertThrows(BadRequestException.class, () -> compiler.compile(
                BizAppReportCompiler.Dialect.POSTGRES,
                orgId,
                tenantId,
                "crm",
                sampleEntity(),
                viewSpec,
                Map.of(),
                false,
                null
        ));
    }

    @Test
    void capsRowLimitAt500() {
        Map<String, Object> viewSpec = Map.of(
                "name", "large_limit",
                "entity", "leads",
                "limit", 99999
        );

        var compiled = compiler.compile(
                BizAppReportCompiler.Dialect.POSTGRES,
                orgId,
                tenantId,
                "crm",
                sampleEntity(),
                viewSpec,
                Map.of(),
                false,
                null
        );

        assertEquals(500, compiled.params().get("limit"));
    }
}
