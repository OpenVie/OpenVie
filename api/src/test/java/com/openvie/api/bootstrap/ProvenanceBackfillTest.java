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

package com.openvie.api.bootstrap;

import com.openvie.api.bizapp.dto.BizAppManifest.*;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.bizapp.repository.BizAppRecordRepository;
import com.openvie.api.bizapp.service.BizAppManifestParser;
import com.openvie.api.bizapp.service.knowledge.BizAppKnowledgeProjector;
import com.openvie.api.document.api.DocumentApi;
import com.openvie.api.document.api.DocumentKind;
import com.openvie.api.document.model.Document;
import com.openvie.api.document.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProvenanceBackfillTest {

    private BizAppInstallationRepository installationRepository;
    private BizAppRecordRepository recordRepository;
    private BizAppKnowledgeProjector projector;
    private BizAppManifestParser manifestParser;
    private DocumentRepository documentRepository;
    private DocumentApi documentApi;
    private ProvenanceBackfillCommand command;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID kbId = UUID.randomUUID();
    private final UUID installationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        installationRepository = mock(BizAppInstallationRepository.class);
        recordRepository = mock(BizAppRecordRepository.class);
        projector = mock(BizAppKnowledgeProjector.class);
        manifestParser = mock(BizAppManifestParser.class);
        documentRepository = mock(DocumentRepository.class);
        documentApi = mock(DocumentApi.class);

        when(projector.resolveKnowledgeBaseId(any(), any())).thenReturn(kbId);

        command = new ProvenanceBackfillCommand(
                installationRepository,
                recordRepository,
                projector,
                manifestParser,
                documentRepository,
                documentApi
        );
    }

    @Test
    void backfillCleansLegacyNamedDocsAndReprojectsActiveRecords() {
        BizAppInstallation installation = new BizAppInstallation();
        installation.setId(installationId);
        installation.setTenantId(tenantId);
        installation.setKind("crm");
        installation.setStatus("ACTIVE");
        installation.setInstalledBy(actorId);
        installation.setManifest(Map.of("kind", "crm"));

        when(installationRepository.findByStatus("ACTIVE")).thenReturn(List.of(installation));

        Manifest manifest = new Manifest(
                "bizapp.v1", "crm", new Localized("CRM", "CRM"), 1, "icon",
                Map.of(), List.of(), List.of(), new Ui(List.of())
        );
        when(manifestParser.parse(any())).thenReturn(manifest);

        UUID recordId = UUID.randomUUID();
        BizAppRecord activeRecord = new BizAppRecord();
        activeRecord.setId(recordId);
        activeRecord.setAppKind("crm");
        activeRecord.setEntity("lead");
        activeRecord.setStatus("ACTIVE");
        activeRecord.setCreatedBy(actorId);

        when(recordRepository.findByTenantIdAndAppKind(tenantId, "crm")).thenReturn(List.of(activeRecord));

        // Legacy old-named documents before upgrade
        Document oldRecordDoc = new Document();
        oldRecordDoc.setId(UUID.randomUUID());
        oldRecordDoc.setFileName("bizapp_crm_lead_" + recordId + ".md"); // old single underscore format
        oldRecordDoc.setDocumentKind(DocumentKind.USER); // legacy row was considered USER

        Document oldDigestDoc = new Document();
        oldDigestDoc.setId(UUID.randomUUID());
        oldDigestDoc.setFileName("bizapp_crm_digest.md");
        oldDigestDoc.setDocumentKind(DocumentKind.USER);

        // Orphan document (for a record that no longer exists)
        Document orphanDoc = new Document();
        orphanDoc.setId(UUID.randomUUID());
        orphanDoc.setFileName("bizapp__crm__lead__00000000-0000-0000-0000-000000000000.md");
        orphanDoc.setDocumentKind(DocumentKind.BIZAPP_RECORD);

        when(documentRepository.findByTenantIdAndKnowledgeBaseIdOrderByCreatedAtDescIdDesc(tenantId, kbId))
                .thenReturn(List.of(oldRecordDoc, oldDigestDoc, orphanDoc));

        // Run backfill
        var report = command.runBackfill(false);

        assertEquals(1, report.reprojectedRecords());
        assertEquals(1, report.refreshedDigests());
        assertEquals(2, report.deletedOldDocuments());
        assertEquals(1, report.cleanedOrphans());

        // Verifications:
        verify(projector).projectRecord(eq(tenantId), eq(actorId), eq(installation), eq(manifest), eq(activeRecord));
        verify(projector).doRefreshDigest(eq(tenantId), eq(actorId), eq(installation), eq(manifest));
        verify(documentApi).deleteByFileName(tenantId, kbId, "bizapp_crm_lead_" + recordId + ".md");
        verify(documentApi).deleteByFileName(tenantId, kbId, "bizapp_crm_digest.md");
        verify(documentApi).deleteByFileName(tenantId, kbId, "bizapp__crm__lead__00000000-0000-0000-0000-000000000000.md");
    }

    @Test
    void backfillIsIdempotentWhenRunTwice() {
        BizAppInstallation installation = new BizAppInstallation();
        installation.setId(installationId);
        installation.setTenantId(tenantId);
        installation.setKind("crm");
        installation.setStatus("ACTIVE");
        installation.setInstalledBy(actorId);
        installation.setManifest(Map.of("kind", "crm"));

        when(installationRepository.findByStatus("ACTIVE")).thenReturn(List.of(installation));
        Manifest manifest = new Manifest("bizapp.v1", "crm", new Localized("CRM", "CRM"), 1, "icon", Map.of(), List.of(), List.of(), new Ui(List.of()));
        when(manifestParser.parse(any())).thenReturn(manifest);
        when(recordRepository.findByTenantIdAndAppKind(tenantId, "crm")).thenReturn(List.of());
        when(documentRepository.findByTenantIdAndKnowledgeBaseIdOrderByCreatedAtDescIdDesc(tenantId, kbId)).thenReturn(List.of());

        var report1 = command.runBackfill(false);
        var report2 = command.runBackfill(false);

        assertEquals(report1.reprojectedRecords(), report2.reprojectedRecords());
        assertEquals(report1.deletedOldDocuments(), report2.deletedOldDocuments());
    }
}
