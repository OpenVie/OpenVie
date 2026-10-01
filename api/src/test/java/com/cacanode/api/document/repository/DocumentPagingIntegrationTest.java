package com.cacanode.api.document.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.cacanode.api.document.enums.DocumentStatus;
import com.cacanode.api.document.enums.DocumentType;
import com.cacanode.api.document.messaging.DocumentIngestionPublisher;
import com.cacanode.api.document.service.DocumentIndexCleanup;
import com.cacanode.api.document.service.DocumentService;
import com.cacanode.api.common.storage.DocumentStorage;
import com.cacanode.api.tenant.api.TenantWorkspaceApi;

@DataJpaTest(properties = "spring.jpa.show-sql=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class DocumentPagingIntegrationTest {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_KNOWLEDGE_BASE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        TenantWorkspaceApi tenantWorkspaceApi = mock(TenantWorkspaceApi.class);
        when(tenantWorkspaceApi.requireActiveKnowledgeBase(TENANT_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(new TenantWorkspaceApi.WorkspaceContext(
                        TENANT_ID, null, KNOWLEDGE_BASE_ID, "Tenant", 0));
        documentService = new DocumentService(
                documentRepository,
                tenantWorkspaceApi,
                mock(DocumentStorage.class),
                mock(DocumentIngestionPublisher.class),
                mock(DocumentIndexCleanup.class),
                mock(ApplicationEventPublisher.class)
        );
    }

    @Test
    void combinesFiltersWithTenantAndKnowledgeBaseIsolation() {
        insert("30000000-0000-0000-0000-000000000001", TENANT_ID, KNOWLEDGE_BASE_ID,
                "Employee Policy.pdf", "PDF", "COMPLETED", "2026-07-14T08:00:00");
        insert("30000000-0000-0000-0000-000000000002", TENANT_ID, KNOWLEDGE_BASE_ID,
                "Employee Policy.txt", "TXT", "COMPLETED", "2026-07-14T09:00:00");
        insert("30000000-0000-0000-0000-000000000003", OTHER_TENANT_ID, KNOWLEDGE_BASE_ID,
                "Employee Policy.pdf", "PDF", "COMPLETED", "2026-07-14T10:00:00");
        insert("30000000-0000-0000-0000-000000000004", TENANT_ID, OTHER_KNOWLEDGE_BASE_ID,
                "Employee Policy.pdf", "PDF", "COMPLETED", "2026-07-14T11:00:00");

        var result = documentService.list(
                TENANT_ID, KNOWLEDGE_BASE_ID, 0, 20, " policy ", DocumentStatus.COMPLETED,
                DocumentType.PDF);

        assertEquals(1, result.size());
        assertEquals("Employee Policy.pdf", result.getFirst().fileName());
        assertEquals(TENANT_ID, findTenant(result.getFirst().id()));
    }

    @Test
    void ordersEqualTimestampsByIdDescendingAndPagesStably() {
        LocalDateTime timestamp = LocalDateTime.parse("2026-07-14T08:00:00");
        for (int index = 1; index <= 3; index++) {
            insert("30000000-0000-0000-0000-00000000000" + index, TENANT_ID, KNOWLEDGE_BASE_ID,
                    "Policy " + index + ".pdf", "PDF", "COMPLETED", timestamp.toString());
        }

        var firstPage = documentService.list(TENANT_ID, KNOWLEDGE_BASE_ID, 0, 2,
                null, null, null);
        var secondPage = documentService.list(TENANT_ID, KNOWLEDGE_BASE_ID, 1, 2,
                null, null, null);

        assertEquals(UUID.fromString("30000000-0000-0000-0000-000000000003"), firstPage.get(0).id());
        assertEquals(UUID.fromString("30000000-0000-0000-0000-000000000002"), firstPage.get(1).id());
        assertEquals(UUID.fromString("30000000-0000-0000-0000-000000000001"), secondPage.getFirst().id());
    }

    @Test
    void returnsAccurateTotalsAcrossMoreThanOneHundredRecordsAndInclusiveDates() {
        for (int index = 0; index < 105; index++) {
            insert(UUID.randomUUID().toString(), TENANT_ID, KNOWLEDGE_BASE_ID,
                    index == 7 ? "literal 100%_ guide.pdf" : "Guide " + index + ".pdf",
                    "PDF", "COMPLETED",
                    index < 50 ? "2026-07-14T23:59:59" : "2026-07-15T00:00:00");
        }

        var day = documentService.listResult(TENANT_ID, KNOWLEDGE_BASE_ID, 0, 20,
                null, null, null, LocalDate.parse("2026-07-14"),
                LocalDate.parse("2026-07-14"), "uploaded", "desc");
        var literal = documentService.listResult(TENANT_ID, KNOWLEDGE_BASE_ID, 0, 20,
                "100%_", null, null, null, null, "uploaded", "desc");

        assertEquals(50, day.totalCount());
        assertEquals(20, day.documents().size());
        assertEquals(1, literal.totalCount());
        assertEquals("literal 100%_ guide.pdf", literal.documents().getFirst().fileName());
    }

    private void insert(
            String id,
            UUID tenantId,
            UUID knowledgeBaseId,
            String fileName,
            String fileType,
            String status,
            String createdAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO documents (
                    id, tenant_id, knowledge_base_id, uploaded_by, file_name, file_type,
                    file_size_bytes, storage_path, status, job_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.fromString(id), tenantId, knowledgeBaseId, UUID.randomUUID(), fileName, fileType,
                100L, "storage/" + id, status, UUID.randomUUID().toString(),
                Timestamp.valueOf(LocalDateTime.parse(createdAt)), Timestamp.valueOf(LocalDateTime.parse(createdAt))
        );
    }

    private UUID findTenant(UUID documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM documents WHERE id = ?", UUID.class, documentId);
    }
}
