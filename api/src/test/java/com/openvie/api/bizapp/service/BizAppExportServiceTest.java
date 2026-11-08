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

import com.openvie.api.bizapp.dto.BizAppDtos.InstalledAppView;
import com.openvie.api.bizapp.dto.BizAppDtos.RecordView;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Localized;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.bizapp.repository.BizAppRecordRepository;
import com.openvie.api.bizapp.service.BizAppExportService.ExportFile;
import com.openvie.api.bizapp.service.BizAppExportService.ExportFormat;
import com.openvie.api.bizapp.service.block.BizAppBlockRegistry;
import com.openvie.api.bizapp.service.block.WebhookOutBlock;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import com.openvie.api.bizapp.service.storage.BizAppStorageStore;
import com.openvie.api.common.exception.custom.BadRequestException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The export contract: one header/row matrix, two encodings. CSV must stay
 * Excel-openable (BOM, quoting) without letting a cell become a formula, XLSX
 * must keep numbers numeric and text literal, and both must cover the whole
 * result rather than the rendered page.
 */
class BizAppExportServiceTest {

    private static final String KIND = "crm";
    private static final String ENTITY = "customer";
    private static final String ROLE = "WORKSPACE_ADMIN";
    private static final String CSV_TYPE = "text/csv;charset=UTF-8";
    private static final String XLSX_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final UUID tenantId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();

    private BizAppRecordService recordService;
    private BizAppReportResolver reportResolver;
    private BizAppInstallService installService;
    private BizAppManifestParser parser;
    private BizAppExportService service;

    @BeforeEach
    void setUp() {
        recordService = mock(BizAppRecordService.class);
        reportResolver = mock(BizAppReportResolver.class);
        installService = mock(BizAppInstallService.class);
        parser = mock(BizAppManifestParser.class);
        service = new BizAppExportService(recordService, reportResolver, installService, parser);

        Map<String, Object> raw = Map.of("schema", "bizapp.v1", "kind", KIND);
        when(installService.getInstalled(tenantId, KIND)).thenReturn(new InstalledAppView(
                KIND, 1, "CRM", "CRM", "icon", "ACTIVE", raw, Map.of(), null, LocalDateTime.now()));
        when(parser.parse(any())).thenReturn(manifest(List.of()));
    }

    // ---------------------------------------------------------------- CSV

    @Test
    void csvKeepsSpecialCharactersInsideQuotedCells() {
        when(parser.parse(any())).thenReturn(manifest(specialFields()));
        when(recordService.exportRows(tenantId, KIND, ENTITY, ROLE, false, BizAppExportService.MAX_RECORDS))
                .thenReturn(List.of(record(specialPayload())));

        String csv = text(service.exportRecords(tenantId, KIND, ENTITY, ROLE, false,
                ExportFormat.CSV, "en"));

        assertEquals('\uFEFF', csv.charAt(0), "UTF-8 BOM keeps Excel from mangling Vietnamese");
        assertTrue(csv.contains("\"note\",\"quote\",\"body\",\"calc\",\"amount\",\"ID\",\"Status\","
                + "\"Created at\",\"Updated at\"\r\n"), csv);
        assertTrue(csv.contains("\"a,b\""), "comma survives inside the quoted cell");
        assertTrue(csv.contains("\"he said \"\"hi\"\"\""), "quotes are doubled");
        assertTrue(csv.contains("\"line1\nline2\""), "newline stays part of the cell");
        assertTrue(csv.contains("\"'=1+1\""), "a formula-looking string is neutralized");
        assertTrue(csv.contains("\"-5\""), "a negative number is never given the quote prefix");
        assertFalse(csv.contains("\"'-5\""), csv);
    }

    // --------------------------------------------------------------- XLSX

    @Test
    void xlsxKeepsNumbersNumericAndTextLiteral() throws IOException {
        when(parser.parse(any())).thenReturn(manifest(specialFields()));
        when(recordService.exportRows(tenantId, KIND, ENTITY, ROLE, false, BizAppExportService.MAX_RECORDS))
                .thenReturn(List.of(record(specialPayload())));

        ExportFile file = service.exportRecords(tenantId, KIND, ENTITY, ROLE, false,
                ExportFormat.XLSX, "en");

        assertEquals(XLSX_TYPE, file.contentType());
        assertTrue(file.filename().matches("bizapp-crm-customer-\\d{8}-\\d{4}\\.xlsx"), file.filename());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            Sheet sheet = workbook.getSheet("Export");
            Row header = sheet.getRow(0);
            assertEquals("note", header.getCell(0).getStringCellValue());
            assertEquals("amount", header.getCell(4).getStringCellValue());
            assertEquals("ID", header.getCell(5).getStringCellValue());

            Row data = sheet.getRow(1);
            assertEquals(CellType.STRING, data.getCell(0).getCellType());
            assertEquals("a,b", data.getCell(0).getStringCellValue());
            assertEquals("he said \"hi\"", data.getCell(1).getStringCellValue());
            assertEquals("line1\nline2", data.getCell(2).getStringCellValue());
            assertEquals(CellType.STRING, data.getCell(3).getCellType());
            assertEquals("=1+1", data.getCell(3).getStringCellValue());
            assertEquals(CellType.NUMERIC, data.getCell(4).getCellType());
            assertEquals(-5.0, data.getCell(4).getNumericCellValue());
            assertEquals(recordId.toString(), data.getCell(5).getStringCellValue());
            assertEquals("ACTIVE", data.getCell(6).getStringCellValue());
        }
    }

    @Test
    void xlsxWritesBooleansAsBooleans() throws IOException {
        Field active = new Field("active", "boolean", true, null, null, null);
        when(parser.parse(any())).thenReturn(manifest(List.of(active)));
        when(recordService.exportRows(tenantId, KIND, ENTITY, ROLE, false, BizAppExportService.MAX_RECORDS))
                .thenReturn(List.of(record(Map.of("active", true))));

        ExportFile file = service.exportRecords(tenantId, KIND, ENTITY, ROLE, false,
                ExportFormat.XLSX, "en");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            Cell cell = workbook.getSheet("Export").getRow(1).getCell(0);
            assertEquals(CellType.BOOLEAN, cell.getCellType());
            assertTrue(cell.getBooleanCellValue());
        }
    }

    // ------------------------------------------------------------- format

    @Test
    void formatParsingIsCaseInsensitiveAndRejectsAnythingElse() {
        assertEquals(ExportFormat.CSV, ExportFormat.parse("csv"));
        assertEquals(ExportFormat.XLSX, ExportFormat.parse("XLSX"));
        assertEquals(ExportFormat.XLSX, ExportFormat.parse("  XlSX "));
        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> ExportFormat.parse("pdf"));
        assertEquals("Unsupported export format pdf", failure.getMessage());
    }

    // ------------------------------------------------------------ records

    @Test
    void recordsExportUsesLocalizedLabelsAndSystemColumns() {
        Field amount = new Field("amount", "number", ENTITY, true, null, null, null, null,
                new Localized("Số tiền", "Amount"));
        Field anonymous = new Field("note", "string", true, null, null, null);
        when(parser.parse(any())).thenReturn(manifest(List.of(amount, anonymous)));
        when(recordService.exportRows(tenantId, KIND, ENTITY, ROLE, false, BizAppExportService.MAX_RECORDS))
                .thenReturn(List.of(record(Map.of("amount", 7, "note", "hi"))));

        ExportFile english = service.exportRecords(tenantId, KIND, ENTITY, ROLE, false,
                ExportFormat.CSV, "en");
        ExportFile vietnamese = service.exportRecords(tenantId, KIND, ENTITY, ROLE, false,
                ExportFormat.CSV, "vi");

        assertEquals(CSV_TYPE, english.contentType());
        assertTrue(english.filename().matches("bizapp-crm-customer-\\d{8}-\\d{4}\\.csv"),
                english.filename());
        assertTrue(text(english).startsWith(
                "\uFEFF\"Amount\",\"note\",\"ID\",\"Status\",\"Created at\",\"Updated at\"\r\n"),
                text(english));
        assertTrue(text(vietnamese).startsWith(
                "\uFEFF\"Số tiền\",\"note\",\"ID\",\"Trạng thái\",\"Ngày tạo\",\"Cập nhật\"\r\n"),
                text(vietnamese));
    }

    @Test
    void recordsExportRejectsAnEntityTheManifestDoesNotDeclare() {
        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> service.exportRecords(tenantId, KIND, "invoice", ROLE, false,
                        ExportFormat.CSV, "en"));
        assertEquals("Unknown entity invoice", failure.getMessage());
    }

    // ------------------------------------------------------------ reports

    @Test
    void reportExportCoversEveryRowAndColumn() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("region", "north");
        row.put("total", 120);
        row.put("orders", 4);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("region", "south");
        second.put("total", 80);
        second.put("orders", 2);
        when(reportResolver.run(tenantId, KIND, "sales", Map.of())).thenReturn(List.of(row, second));

        ExportFile file = service.exportReport(tenantId, KIND, "sales", ExportFormat.CSV, "en", null);

        assertTrue(file.filename().matches("bizapp-crm-sales-\\d{8}-\\d{4}\\.csv"), file.filename());
        assertEquals("\uFEFF\"region\",\"total\",\"orders\"\r\n"
                + "\"north\",\"120\",\"4\"\r\n"
                + "\"south\",\"80\",\"2\"\r\n", text(file));
    }

    @Test
    void anEmptyReportStillDocumentsItsDeclaredColumns() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", "sales");
        view.put("entity", ENTITY);
        view.put("groupBy", List.of("region"));
        view.put("metrics", List.of(Map.of("fn", "sum", "field", "amount", "as", "total")));
        Block report = new Block("report", "report", Map.of("views", List.of(view)));
        when(parser.parse(any())).thenReturn(manifest(List.of(), List.of(report)));
        when(reportResolver.run(tenantId, KIND, "sales", Map.of())).thenReturn(List.of());

        ExportFile file = service.exportReport(tenantId, KIND, "sales", ExportFormat.CSV, "en", null);

        assertEquals("\uFEFF\"region\",\"total\"\r\n", text(file));
    }

    @Test
    void anEmptyReportWithoutADeclaredShapeWritesNoHeader() {
        when(reportResolver.run(tenantId, KIND, "sales", Map.of())).thenReturn(List.of());

        ExportFile file = service.exportReport(tenantId, KIND, "sales", ExportFormat.CSV, "en", null);

        assertEquals("\uFEFF", text(file));
    }

    // ---------------------------------------------------------- the fence

    @Test
    void bothFormatsShareTheSameHeaderOrder() throws IOException {
        when(parser.parse(any())).thenReturn(manifest(specialFields()));
        when(recordService.exportRows(tenantId, KIND, ENTITY, ROLE, false, BizAppExportService.MAX_RECORDS))
                .thenReturn(List.of(record(specialPayload())));

        String csvHeader = text(service.exportRecords(tenantId, KIND, ENTITY, ROLE, false,
                ExportFormat.CSV, "en")).split("\r\n")[0];
        ExportFile xlsx = service.exportRecords(tenantId, KIND, ENTITY, ROLE, false,
                ExportFormat.XLSX, "en");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx.content()))) {
            Row header = workbook.getSheet("Export").getRow(0);
            StringBuilder fromSheet = new StringBuilder("\uFEFF");
            for (int index = 0; index < header.getLastCellNum(); index++) {
                if (index > 0) {
                    fromSheet.append(',');
                }
                fromSheet.append('"').append(header.getCell(index).getStringCellValue()).append('"');
            }
            assertEquals(csvHeader, fromSheet.toString());
        }
    }

    @Test
    void theRowCapIsEnforcedByTheRecordService() {
        BizAppRecordService capped = recordExportFixture(10_001);

        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> capped.exportRows(tenantId, KIND, ENTITY, ROLE, false, 10_000));

        assertEquals("Export is limited to 10000 records; narrow the query", failure.getMessage());
    }

    @Test
    void theRecordServiceWalksEveryPageInListingOrder() {
        BizAppRecordService paged = recordExportFixture(250);

        List<RecordView> rows = paged.exportRows(tenantId, KIND, ENTITY, ROLE, false, 10_000);

        assertEquals(250, rows.size());
        assertNotEquals(rows.get(0).id(), rows.get(249).id());
        assertEquals("note-249", rows.get(249).payload().get("note"));
    }

    // ------------------------------------------------------------ fixture

    /**
     * A record service whose storage returns {@code total} rows of 100 per
     * page, so the cap and the page walk are exercised on real code.
     */
    private BizAppRecordService recordExportFixture(long total) {
        BizAppInstallationRepository installations = mock(BizAppInstallationRepository.class);
        BizAppStorageRouter router = mock(BizAppStorageRouter.class);
        BizAppStorageStore store = mock(BizAppStorageStore.class);
        BizAppManifestParser manifestParser = mock(BizAppManifestParser.class);

        BizAppInstallation installation = new BizAppInstallation();
        installation.setId(UUID.randomUUID());
        installation.setTenantId(tenantId);
        installation.setKind(KIND);
        installation.setVersion(1);
        installation.setManifest(Map.of("schema", "bizapp.v1"));
        installation.setStatus("ACTIVE");
        when(installations.findByTenantIdAndKindAndStatus(tenantId, KIND, "ACTIVE"))
                .thenReturn(Optional.of(installation));
        when(manifestParser.parse(any())).thenReturn(manifest(List.of()));
        when(router.route(KIND, ENTITY)).thenReturn(store);

        List<BizAppRecord> stored = new ArrayList<>();
        for (int index = 0; index < Math.min(total, 250); index++) {
            BizAppRecord record = new BizAppRecord();
            record.setId(UUID.randomUUID());
            record.setTenantId(tenantId);
            record.setAppKind(KIND);
            record.setEntity(ENTITY);
            record.setStatus("ACTIVE");
            record.setPayload(Map.of("note", "note-" + index));
            stored.add(record);
        }
        when(store.list(eq(tenantId), eq(KIND), eq(ENTITY), eq(false), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(4);
                    long offset = pageable.getOffset();
                    long pageTotal = total;
                    if (offset >= stored.size()) {
                        return new PageImpl<BizAppRecord>(List.of(), pageable, pageTotal);
                    }
                    int from = (int) offset;
                    int to = (int) Math.min(from + pageable.getPageSize(), stored.size());
                    return new PageImpl<>(stored.subList(from, to), pageable, pageTotal);
                });

        return new BizAppRecordService(mock(BizAppRecordRepository.class), installations, manifestParser,
                mock(BizAppPayloadValidator.class), mock(BizAppBlockRegistry.class),
                mock(BizAppRuntime.class), mock(WebhookOutBlock.class), router);
    }

    private Manifest manifest(List<Field> fields) {
        return manifest(fields, List.of());
    }

    private Manifest manifest(List<Field> fields, List<Block> blocks) {
        Entity entity = new Entity(ENTITY, new Localized("Customer", "Khách hàng"), fields, Map.of());
        return new Manifest("bizapp.v1", KIND, new Localized("CRM", "CRM"), 1, "icon", "jsonb",
                Map.of(), List.of(entity), blocks, null);
    }

    private List<Field> specialFields() {
        return List.of(
                new Field("note", "string", true, null, null, null),
                new Field("quote", "string", true, null, null, null),
                new Field("body", "string", true, null, null, null),
                new Field("calc", "string", true, null, null, null),
                new Field("amount", "number", true, null, null, null));
    }

    private Map<String, Object> specialPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("note", "a,b");
        payload.put("quote", "he said \"hi\"");
        payload.put("body", "line1\nline2");
        payload.put("calc", "=1+1");
        payload.put("amount", -5);
        return payload;
    }

    private RecordView record(Map<String, Object> payload) {
        return new RecordView(recordId, KIND, ENTITY, payload, Map.of(), "ACTIVE", UUID.randomUUID(),
                LocalDateTime.of(2026, 1, 2, 3, 4), LocalDateTime.of(2026, 1, 2, 3, 5));
    }

    private String text(ExportFile file) {
        return new String(file.content(), StandardCharsets.UTF_8);
    }
}
