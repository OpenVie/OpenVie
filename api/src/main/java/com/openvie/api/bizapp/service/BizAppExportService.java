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

package com.openvie.api.bizapp.service;

import com.openvie.api.bizapp.dto.BizAppDtos.RecordView;
import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Localized;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.common.exception.custom.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Record/report export: the same tenant-scoped, manifest-fenced data the
 * registry and report views render, written as CSV or XLSX.
 *
 * <p>Both formats share one header/row matrix so a CSV and an XLSX export of
 * the same request are identical apart from encoding. Cells stay typed
 * ({@link Number}, {@link Boolean}, {@link String}) until a renderer decides;
 * CSV stringifies, XLSX keeps numbers numeric.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP")
public class BizAppExportService {

    /** Registry export ceiling; above it the caller must narrow the query. */
    public static final int MAX_RECORDS = 10_000;

    private static final String CSV_CONTENT_TYPE = "text/csv;charset=UTF-8";
    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final int SHEET_ROW_WINDOW = 100;
    private static final String SHEET_NAME = "Export";
    /** XLSX refuses text cells beyond 32767 characters. */
    private static final int MAX_CELL_CHARS = 32_767;

    private final BizAppRecordService recordService;
    private final BizAppReportResolver reportResolver;
    private final BizAppInstallService installService;
    private final BizAppManifestParser parser;

    /** The wire formats the export endpoints accept. */
    public enum ExportFormat {
        CSV("csv", CSV_CONTENT_TYPE),
        XLSX("xlsx", XLSX_CONTENT_TYPE);

        private final String extension;
        private final String contentType;

        ExportFormat(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public String extension() {
            return extension;
        }

        public String contentType() {
            return contentType;
        }

        public static ExportFormat parse(String value) {
            if (value == null || value.isBlank()) {
                return CSV;
            }
            try {
                return ExportFormat.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new BadRequestException("Unsupported export format " + value);
            }
        }
    }

    /** One rendered download: bytes plus the headers the controller needs. */
    public record ExportFile(byte[] content, String filename, String contentType) {
    }

    // ----------------------------------------------------------------- rows

    /**
     * Every record of one entity, in listing order, headed by the entity's
     * declared fields followed by the system columns.
     */
    public ExportFile exportRecords(UUID tenantId, String appKind, String entity,
            String role, boolean includeArchived, ExportFormat format, String locale) {
        Manifest manifest = manifest(tenantId, appKind);
        Entity declaration = manifest.entities() == null ? null
                : manifest.entities().stream()
                        .filter(candidate -> entity.equals(candidate.name()))
                        .findFirst()
                        .orElse(null);
        if (declaration == null) {
            throw new BadRequestException("Unknown entity " + entity);
        }
        List<Field> fields = declaration.fields() == null ? List.of() : declaration.fields();
        List<RecordView> records = recordService.exportRows(
                tenantId, appKind, entity, role, includeArchived, MAX_RECORDS);

        boolean vietnamese = "vi".equals(locale);
        List<String> headers = new ArrayList<>(fields.size() + 4);
        for (Field field : fields) {
            headers.add(label(field, vietnamese));
        }
        headers.add("ID");
        headers.add(vietnamese ? "Trạng thái" : "Status");
        headers.add(vietnamese ? "Ngày tạo" : "Created at");
        headers.add(vietnamese ? "Cập nhật" : "Updated at");

        List<List<Object>> rows = new ArrayList<>(records.size());
        for (RecordView record : records) {
            List<Object> cells = new ArrayList<>(headers.size());
            Map<String, Object> payload = record.payload() == null ? Map.of() : record.payload();
            for (Field field : fields) {
                cells.add(cellValue(payload.get(field.name())));
            }
            cells.add(record.id());
            cells.add(record.status());
            cells.add(record.createdAt());
            cells.add(record.updatedAt());
            rows.add(cells);
        }
        return render(format, "bizapp-" + segment(appKind) + "-" + segment(entity)
                + "-" + LocalDateTime.now().format(FILE_STAMP), headers, rows);
    }

    /**
     * All rows and all columns of one report view. Headers come from the row
     * maps themselves; an empty result falls back to the view declaration so
     * the file still documents its columns.
     */
    public ExportFile exportReport(UUID tenantId, String appKind, String viewName,
            ExportFormat format, String locale, Map<String, Object> params) {
        Manifest manifest = manifest(tenantId, appKind);
        List<Map<String, Object>> report = reportResolver.run(
                tenantId, appKind, viewName, params == null ? Map.of() : params);

        List<String> headers = report.isEmpty()
                ? declaredHeaders(manifest, viewName)
                : new ArrayList<>(report.get(0).keySet());

        List<List<Object>> rows = new ArrayList<>(report.size());
        for (Map<String, Object> row : report) {
            List<Object> cells = new ArrayList<>(headers.size());
            for (String header : headers) {
                cells.add(row == null ? null : cellValue(row.get(header)));
            }
            rows.add(cells);
        }
        return render(format, "bizapp-" + segment(appKind) + "-" + segment(viewName)
                + "-" + LocalDateTime.now().format(FILE_STAMP), headers, rows);
    }

    // -------------------------------------------------------------- render

    private ExportFile render(ExportFormat format, String baseName,
            List<String> headers, List<List<Object>> rows) {
        byte[] content = format == ExportFormat.XLSX
                ? toXlsx(headers, rows)
                : toCsv(headers, rows).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new ExportFile(content, baseName + "." + format.extension(), format.contentType());
    }

    /**
     * RFC 4180 CSV behind a UTF-8 BOM so Excel reads Vietnamese text, with
     * every cell quoted. Text that a spreadsheet would evaluate as a formula
     * is prefixed with an apostrophe; numbers keep their bare form.
     */
    private String toCsv(List<String> headers, List<List<Object>> rows) {
        StringBuilder csv = new StringBuilder(64 * (rows.size() + 1));
        csv.append('\uFEFF');
        if (headers.isEmpty()) {
            // Nothing to name the columns: an empty file, not a stray blank row.
            return csv.toString();
        }
        appendCsvRow(csv, headers);
        for (List<Object> row : rows) {
            appendCsvRow(csv, row);
        }
        return csv.toString();
    }

    private void appendCsvRow(StringBuilder csv, List<?> cells) {
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                csv.append(',');
            }
            csv.append('"').append(csvCell(cells.get(index)).replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    private String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text && !text.isEmpty() && isFormulaLead(text.charAt(0))) {
            return "'" + text;
        }
        return String.valueOf(value);
    }

    private boolean isFormulaLead(char lead) {
        return lead == '=' || lead == '+' || lead == '-' || lead == '@';
    }

    /**
     * Streams the matrix into a single sheet. Only literal value setters are
     * used — a cell is never a formula — and the temp files the streaming
     * writer spills to are disposed after the bytes are read out.
     */
    private byte[] toXlsx(List<String> headers, List<List<Object>> rows) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(SHEET_ROW_WINDOW);
        workbook.setCompressTempFiles(true);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024)) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            writeXlsxRow(sheet.createRow(0), headers);
            for (int index = 0; index < rows.size(); index++) {
                writeXlsxRow(sheet.createRow(index + 1), rows.get(index));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not render the XLSX export", failure);
        } finally {
            // SXSSFWorkbook.close() disposes the spill files; dispose() itself
            // is deprecated as of POI 5.5.
            try {
                workbook.close();
            } catch (IOException ignored) {
                log.warn("Closing the streaming workbook failed: {}", ignored.getMessage());
            }
        }
    }

    private void writeXlsxRow(Row row, List<?> cells) {
        for (int index = 0; index < cells.size(); index++) {
            Object value = cells.get(index);
            if (value == null) {
                continue;
            }
            Cell cell = row.createCell(index);
            if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else if (value instanceof Boolean flag) {
                cell.setCellValue(flag);
            } else {
                String text = String.valueOf(value);
                cell.setCellValue(text.length() > MAX_CELL_CHARS
                        ? text.substring(0, MAX_CELL_CHARS) : text);
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private Manifest manifest(UUID tenantId, String appKind) {
        return parser.parse(installService.getInstalled(tenantId, appKind).manifest());
    }

    /** Field label for the locale, falling back to the declared name. */
    private String label(Field field, boolean vietnamese) {
        Localized localized = field.label();
        String value = localized == null ? null
                : vietnamese ? localized.vi() : localized.en();
        return value == null || value.isBlank() ? field.name() : value;
    }

    /** Scalars keep their type; structured values degrade to their text form. */
    private Object cellValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    /**
     * Column names of a report view that returned no rows: the explicit
     * selection, else the grouping columns followed by the metric aliases.
     */
    private List<String> declaredHeaders(Manifest manifest, String viewName) {
        Map<String, Object> view = reportView(manifest, viewName);
        List<String> headers = new ArrayList<>();
        if (view == null) {
            return headers;
        }
        headers.addAll(strings(view.get("fields")));
        if (!headers.isEmpty()) {
            return headers;
        }
        headers.addAll(strings(view.get("groupBy")));
        if (view.get("metrics") instanceof List<?> metrics) {
            for (Object metric : metrics) {
                if (metric instanceof Map<?, ?> spec && spec.get("as") != null) {
                    headers.add(String.valueOf(spec.get("as")));
                }
            }
        }
        return headers;
    }

    private Map<String, Object> reportView(Manifest manifest, String viewName) {
        if (manifest.blocks() == null) {
            return null;
        }
        for (var block : manifest.blocks()) {
            if (!"report".equals(block.type()) || block.params() == null
                    || !(block.params().get("views") instanceof List<?> views)) {
                continue;
            }
            for (Object view : views) {
                if (view instanceof Map<?, ?> spec && viewName.equals(spec.get("name"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) spec;
                    return typed;
                }
            }
        }
        return null;
    }

    private List<String> strings(Object value) {
        List<String> values = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
        }
        return values;
    }

    /** Path segment as a filename part: no quotes, separators or spaces. */
    private String segment(String value) {
        if (value == null || value.isBlank()) {
            return "export";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
