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

import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
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
import com.openvie.api.tenant.api.TenantKnowledgeApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Operational command for re-projecting legacy BizApp knowledge documents
 * into flat-named system documents tagged with {@link DocumentKind}.
 *
 * <p>Idempotent and safe to rerun. Supports dry-run execution.
 */
@Component
@ConditionalOnProperty(name = "app.command.mode", havingValue = "bizapp-provenance-backfill")
@RequiredArgsConstructor
@Slf4j(topic = "PROVENANCE-BACKFILL")
public class ProvenanceBackfillCommand implements ApplicationRunner {

    static final String OPTION_DRY_RUN = "dry-run";

    private final BizAppInstallationRepository installationRepository;
    private final BizAppRecordRepository recordRepository;
    private final BizAppKnowledgeProjector projector;
    private final BizAppManifestParser manifestParser;
    private final DocumentRepository documentRepository;
    private final DocumentApi documentApi;

    @Override
    public void run(ApplicationArguments args) {
        boolean dryRun = args.containsOption(OPTION_DRY_RUN);
        runBackfill(dryRun);
    }

    @Transactional
    public BackfillReport runBackfill(boolean dryRun) {
        log.info("Starting BizApp knowledge provenance backfill (dryRun={})", dryRun);
        int reprojectedRecords = 0;
        int refreshedDigests = 0;
        int deletedOldDocuments = 0;
        int cleanedOrphans = 0;

        List<BizAppInstallation> activeInstallations = installationRepository.findByStatus("ACTIVE");

        for (BizAppInstallation installation : activeInstallations) {
            UUID tenantId = installation.getTenantId();
            UUID kbId = projector.resolveKnowledgeBaseId(tenantId, installation);
            Manifest manifest = manifestParser.parse(installation.getManifest());

            List<BizAppRecord> records = recordRepository.findByTenantIdAndAppKind(tenantId, installation.getKind());

            for (BizAppRecord record : records) {
                if ("ACTIVE".equalsIgnoreCase(record.getStatus())) {
                    if (!dryRun) {
                        projector.projectRecord(tenantId, record.getCreatedBy(), installation, manifest, record);
                    }
                    reprojectedRecords++;
                }
            }

            if (!dryRun) {
                projector.doRefreshDigest(tenantId, installation.getInstalledBy(), installation, manifest);
            }
            refreshedDigests++;

            List<Document> kbDocs = documentRepository.findByTenantIdAndKnowledgeBaseIdOrderByCreatedAtDescIdDesc(tenantId, kbId);
            for (Document doc : kbDocs) {
                String name = doc.getFileName();
                // Check if old naming scheme: starts with "bizapp_" but not "bizapp__"
                if (name.startsWith("bizapp_") && !name.startsWith("bizapp__")) {
                    log.info("Deleting legacy system document {} (dryRun={})", name, dryRun);
                    if (!dryRun) {
                        documentApi.deleteByFileName(tenantId, kbId, name);
                    }
                    deletedOldDocuments++;
                } else if (name.startsWith("bizapp__" + installation.getKind() + "__") && !name.endsWith("__digest.md")) {
                    // Check if orphan (record no longer exists or archived)
                    boolean existsActive = records.stream()
                            .anyMatch(r -> name.equals("bizapp__" + r.getAppKind() + "__" + r.getEntity() + "__" + r.getId() + ".md")
                                    && "ACTIVE".equalsIgnoreCase(r.getStatus()));
                    if (!existsActive) {
                        log.info("Cleaning orphan projected document {} (dryRun={})", name, dryRun);
                        if (!dryRun) {
                            documentApi.deleteByFileName(tenantId, kbId, name);
                        }
                        cleanedOrphans++;
                    }
                }
            }
        }

        log.info("Backfill completed: reprojectedRecords={}, refreshedDigests={}, deletedOldDocs={}, cleanedOrphans={}",
                reprojectedRecords, refreshedDigests, deletedOldDocuments, cleanedOrphans);

        return new BackfillReport(reprojectedRecords, refreshedDigests, deletedOldDocuments, cleanedOrphans);
    }

    public record BackfillReport(
            int reprojectedRecords,
            int refreshedDigests,
            int deletedOldDocuments,
            int cleanedOrphans
    ) {}
}
