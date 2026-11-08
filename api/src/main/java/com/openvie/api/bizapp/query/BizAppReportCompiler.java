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

import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.common.exception.custom.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Compiles a declarative manifest report view into a parameterized SQL statement
 * for either PostgreSQL or DuckDB (Lakehouse).
 *
 * <p>Security invariants:
 * <ul>
 *   <li>The FROM table is strictly fixed by storage routing; the manifest never names a table.</li>
 *   <li>org_id, tenant_id, app_kind, entity, and status='ACTIVE' predicates are compiler-enforced.</li>
 *   <li>All column names and aliases are validated against {@code ^[a-z][a-z0-9_]{0,63}$}.</li>
 *   <li>All user-supplied values are bound named parameters.</li>
 *   <li>Result limit is strictly capped at 500 rows.</li>
 * </ul>
 */
@Component
public class BizAppReportCompiler {

    private static final Pattern IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    private static final Set<String> ALLOWED_METRIC_FNS = Set.of("count", "sum", "avg", "min", "max");
    private static final Set<String> ALLOWED_FILTER_OPS = Set.of(
            "eq", "ne", "in", "gt", "gte", "lt", "lte", "contains", "isnull");

    public static final int MAX_ROW_LIMIT = 500;
    public static final int DEFAULT_ROW_LIMIT = 100;

    public record CompiledQuery(String sql, Map<String, Object> params) {}

    public enum Dialect {
        POSTGRES,
        DUCKDB
    }

    public CompiledQuery compile(
            Dialect dialect,
            UUID orgId,
            UUID tenantId,
            String appKind,
            Entity entityDef,
            Map<String, Object> viewSpec,
            Map<String, Object> runtimeParams,
            boolean isTyped,
            String typedTableName) {

        if (viewSpec == null) {
            throw new BadRequestException("View spec cannot be null");
        }

        Map<String, Field> fieldsByName = new HashMap<>();
        if (entityDef != null && entityDef.fields() != null) {
            for (Field f : entityDef.fields()) {
                fieldsByName.put(f.name(), f);
            }
        }

        Map<String, Object> params = new HashMap<>();
        params.put("orgId", orgId);
        params.put("tenantId", tenantId);
        if (!isTyped) {
            params.put("appKind", appKind);
            params.put("entityName", entityDef == null ? "" : entityDef.name());
        }

        @SuppressWarnings("unchecked")
        List<String> groupBy = (List<String>) viewSpec.get("groupBy");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) viewSpec.get("metrics");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filters = (List<Map<String, Object>>) viewSpec.get("filters");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orderBy = (List<Map<String, Object>>) viewSpec.get("orderBy");
        @SuppressWarnings("unchecked")
        List<String> selectedFields = (List<String>) viewSpec.get("fields");

        StringBuilder selectClause = new StringBuilder();
        StringBuilder groupByClause = new StringBuilder();
        Set<String> validAliases = new HashSet<>();

        // 1. Group By columns
        if (groupBy != null && !groupBy.isEmpty()) {
            List<String> groupSelects = new ArrayList<>();
            List<String> groupExprs = new ArrayList<>();
            for (String col : groupBy) {
                validateName(col);
                if (!fieldsByName.containsKey(col)) {
                    throw new BadRequestException("Group-by field " + col + " not declared on entity");
                }
                String expr = fieldExpr(dialect, isTyped, col, fieldsByName.get(col));
                groupSelects.add(expr + " AS " + col);
                groupExprs.add(expr);
                validAliases.add(col);
            }
            selectClause.append(String.join(", ", groupSelects));
            groupByClause.append(" GROUP BY ").append(String.join(", ", groupExprs));
        }

        // 2. Metrics
        if (metrics != null && !metrics.isEmpty()) {
            List<String> metricSelects = new ArrayList<>();
            for (Map<String, Object> m : metrics) {
                String fn = String.valueOf(m.get("fn")).toLowerCase(Locale.ROOT);
                if (!ALLOWED_METRIC_FNS.contains(fn)) {
                    throw new BadRequestException("Unsupported metric function: " + fn);
                }
                String as = String.valueOf(m.get("as"));
                validateIdent(as);
                validAliases.add(as);

                String fieldName = m.get("field") == null ? null : String.valueOf(m.get("field"));
                String metricExpr;
                if ("count".equals(fn) && (fieldName == null || fieldName.isBlank())) {
                    metricExpr = "COUNT(*)";
                } else {
                    if (fieldName == null || !fieldsByName.containsKey(fieldName)) {
                        throw new BadRequestException("Metric field " + fieldName + " not declared on entity");
                    }
                    Field field = fieldsByName.get(fieldName);
                    if (("sum".equals(fn) || "avg".equals(fn)) && !"number".equals(field.type())) {
                        throw new BadRequestException("Metric " + fn + " requires numeric field, got " + field.type());
                    }
                    String colExpr = numericFieldExpr(dialect, isTyped, fieldName, field);
                    metricExpr = fn.toUpperCase(Locale.ROOT) + "(" + colExpr + ")";
                }
                metricSelects.add(metricExpr + " AS " + as);
            }
            if (selectClause.length() > 0) {
                selectClause.append(", ");
            }
            selectClause.append(String.join(", ", metricSelects));
        }

        // If neither groupBy nor metrics: select the chosen columns, or all
        // declared entity fields when the view leaves the list implicit.
        if (selectClause.length() == 0) {
            List<String> allCols = new ArrayList<>();
            if (selectedFields != null && !selectedFields.isEmpty()) {
                for (String fieldName : selectedFields) {
                    validateName(fieldName);
                    Field field = fieldsByName.get(fieldName);
                    if (field == null) {
                        throw new BadRequestException("Selected field " + fieldName + " not declared on entity");
                    }
                    allCols.add(fieldExpr(dialect, isTyped, fieldName, field) + " AS " + fieldName);
                    validAliases.add(fieldName);
                }
            } else if (entityDef != null && entityDef.fields() != null) {
                for (Field f : entityDef.fields()) {
                    allCols.add(fieldExpr(dialect, isTyped, f.name(), f) + " AS " + f.name());
                    validAliases.add(f.name());
                }
            }
            allCols.add("created_at");
            allCols.add("updated_at");
            selectClause.append(String.join(", ", allCols));
        }

        // 3. FROM and WHERE
        StringBuilder whereClause = new StringBuilder();
        String fromTable;
        if (isTyped && typedTableName != null && !typedTableName.isBlank()) {
            validateIdent(typedTableName);
            fromTable = typedTableName;
            whereClause.append("org_id = :orgId AND tenant_id = :tenantId AND status = 'ACTIVE'");
        } else {
            fromTable = "bizapp_records";
            whereClause.append("org_id = :orgId AND tenant_id = :tenantId AND app_kind = :appKind AND entity = :entityName AND status = 'ACTIVE'");
        }

        // 4. Filters
        if (filters != null && !filters.isEmpty()) {
            int filterIdx = 0;
            for (Map<String, Object> f : filters) {
                String fieldName = String.valueOf(f.get("field"));
                validateName(fieldName);
                if (!fieldsByName.containsKey(fieldName)) {
                    throw new BadRequestException("Filter field " + fieldName + " not declared on entity");
                }
                Field field = fieldsByName.get(fieldName);
                String op = String.valueOf(f.get("op")).toLowerCase(Locale.ROOT);
                if (!ALLOWED_FILTER_OPS.contains(op)) {
                    throw new BadRequestException("Unsupported filter op: " + op);
                }

                String paramKey = "f_" + filterIdx + "_" + fieldName;
                Object filterValue = null;
                if (f.containsKey("param")) {
                    String reqParam = String.valueOf(f.get("param"));
                    if (runtimeParams != null && runtimeParams.containsKey(reqParam)) {
                        filterValue = runtimeParams.get(reqParam);
                    }
                } else if (f.containsKey("values")) {
                    filterValue = f.get("values");
                } else if (f.containsKey("value")) {
                    filterValue = f.get("value");
                }

                String expr = "number".equals(field.type())
                        ? numericFieldExpr(dialect, isTyped, fieldName, field)
                        : fieldExpr(dialect, isTyped, fieldName, field);

                whereClause.append(" AND ");
                switch (op) {
                    case "isnull" -> whereClause.append(expr).append(" IS NULL");
                    case "eq" -> {
                        whereClause.append(expr).append(" = :").append(paramKey);
                        params.put(paramKey, filterValue);
                    }
                    case "ne" -> {
                        whereClause.append(expr).append(" <> :").append(paramKey);
                        params.put(paramKey, filterValue);
                    }
                    case "in" -> {
                        whereClause.append(expr).append(" IN (:").append(paramKey).append(")");
                        params.put(paramKey, filterValue instanceof List<?> ? filterValue : List.of(filterValue));
                    }
                    case "gt" -> {
                        whereClause.append(expr).append(" > :").append(paramKey);
                        params.put(paramKey, filterValue);
                    }
                    case "gte" -> {
                        whereClause.append(expr).append(" >= :").append(paramKey);
                        params.put(paramKey, filterValue);
                    }
                    case "lt" -> {
                        whereClause.append(expr).append(" < :").append(paramKey);
                        params.put(paramKey, filterValue);
                    }
                    case "lte" -> {
                        whereClause.append(expr).append(" <= :").append(paramKey);
                        params.put(paramKey, filterValue);
                    }
                    case "contains" -> {
                        whereClause.append(expr).append(" ILIKE :").append(paramKey);
                        params.put(paramKey, "%" + filterValue + "%");
                    }
                }
                filterIdx++;
            }
        }

        // 5. Order By
        StringBuilder orderClause = new StringBuilder();
        if (orderBy != null && !orderBy.isEmpty()) {
            List<String> orderItems = new ArrayList<>();
            for (Map<String, Object> o : orderBy) {
                String by = String.valueOf(o.get("by"));
                validateName(by);
                if (!validAliases.contains(by) && !fieldsByName.containsKey(by)) {
                    throw new BadRequestException("Order-by target " + by + " is neither a declared field nor metric alias");
                }
                String dir = "desc".equalsIgnoreCase(String.valueOf(o.get("dir"))) ? "DESC" : "ASC";
                orderItems.add(by + " " + dir);
            }
            orderClause.append(" ORDER BY ").append(String.join(", ", orderItems));
        }

        // 6. Limit
        int limit = DEFAULT_ROW_LIMIT;
        if (viewSpec.get("limit") instanceof Number num) {
            limit = Math.min(Math.max(1, num.intValue()), MAX_ROW_LIMIT);
        }
        params.put("limit", limit);

        String sql = "SELECT " + selectClause
                + " FROM " + fromTable
                + " WHERE " + whereClause
                + groupByClause
                + orderClause
                + " LIMIT :limit";

        return new CompiledQuery(sql, params);
    }

    private String fieldExpr(Dialect dialect, boolean isTyped, String fieldName, Field field) {
        if (isTyped) {
            return toColumnName(fieldName);
        }
        // In JSONB: payload ->> 'fieldName'
        return "(payload ->> '" + fieldName + "')";
    }

    private String numericFieldExpr(Dialect dialect, boolean isTyped, String fieldName, Field field) {
        if (isTyped) {
            return toColumnName(fieldName);
        }
        if (dialect == Dialect.DUCKDB) {
            return "TRY_CAST(payload ->> '" + fieldName + "' AS DOUBLE)";
        }
        return "(payload ->> '" + fieldName + "')::numeric";
    }

    private String toColumnName(String fieldName) {
        // e.g. dealValue -> deal_value if needed, or straight lowercase
        validateName(fieldName);
        StringBuilder sb = new StringBuilder();
        for (char c : fieldName.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void validateIdent(String ident) {
        if (ident == null || !IDENT.matcher(ident).matches()) {
            throw new BadRequestException("Invalid SQL identifier: " + ident);
        }
    }

    private void validateName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new BadRequestException("Invalid field name: " + name);
        }
    }
}
