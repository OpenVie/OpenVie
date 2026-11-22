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

package com.openvie.api.common.retention;

import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppConnectorJobRepository;
import com.openvie.api.bizapp.repository.BizAppRecordRepository;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.event.AuditLogEvent;
import com.openvie.api.tenant.api.TenantStatus;
import com.openvie.api.tenant.model.Tenant;
import com.openvie.api.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Daily retention sweep over soft-archived rows.
 *
 * <p>Workspaces are never hard-deleted here: their FKs span documents, chat,
 * knowledge bases, workflow and audit history, so hard removal stays an
 * operator runbook (see docs/BIZAPPS.md §Retention). The sweep reports eligible
 * workspaces in the audit trail and logs them. BizApp records are self-contained
 * (connector jobs are the only child rows) and are purged only when
 * {@code app.retention.bizapp-record-purge-enabled=true}.
 *
 * <p>{@code legalHold} always wins: held workspaces are excluded from the
 * workspace report and the record purge query, and archived workspaces are
 * excluded from record purge until their own reviewed removal.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "RETENTION")
public class RetentionSweepService {

    private static final int WORKSPACE_PAGE_SIZE = 200;

    private final RetentionProperties properties;
    private final TenantRepository tenantRepository;
    private final BizAppRecordRepository recordRepository;
    private final BizAppConnectorJobRepository jobRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(
            fixedDelayString = "${app.retention.scan-delay-ms:86400000}",
            initialDelayString = "${app.retention.initial-delay-ms:300000}")
    @Transactional
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        int workspaceCandidates = reportWorkspaceCandidates(now);
        int[] recordResult = sweepRecords(now);
        if (workspaceCandidates > 0 || recordResult[0] > 0 || recordResult[1] > 0) {
            log.info("Retention sweep: workspaceCandidates={} recordCandidates={} recordsPurged={} "
                            + "(workspaceDays={}, recordDays={}, recordPurgeEnabled={})",
                    workspaceCandidates, recordResult[0], recordResult[1],
                    properties.workspaceDays(), properties.recordDays(), properties.recordPurgeEnabled());
        }
    }

    /** Reports eligible workspaces; deletion is deliberately manual. */
    int reportWorkspaceCandidates(LocalDateTime now) {
        int days = properties.workspaceDays();
        if (days <= 0) {
            return 0;
        }
        LocalDateTime cutoff = now.minusDays(days);
        Page<Tenant> page = tenantRepository
                .findByStatusAndArchivedAtBeforeAndLegalHoldFalse(
                        TenantStatus.ARCHIVED, cutoff, PageRequest.of(0, WORKSPACE_PAGE_SIZE));
        for (Tenant workspace : page.getContent()) {
            LocalDateTime eligibleAt = properties.purgeEligibleAt(workspace.getArchivedAt(), days);
            log.warn("Retention: workspace {} archived {} is purge-eligible (legalHold=false, retention {}d)",
                    workspace.getId(), workspace.getArchivedAt(), days);
            eventPublisher.publishEvent(AuditLogEvent.builder(this)
                    .tenantId(workspace.getId())
                    .action(LogAction.RETENTION_SWEEP)
                    .resourceType("workspace")
                    .resourceId(workspace.getId())
                    .metadata(Map.of(
                            "archivedAt", String.valueOf(workspace.getArchivedAt()),
                            "retentionDays", days,
                            "purgeEligibleAt", String.valueOf(eligibleAt),
                            "action", "manual-review-required"))
                    .build());
        }
        return (int) page.getTotalElements();
    }

    /** @return {eligible, purged} */
    int[] sweepRecords(LocalDateTime now) {
        int days = properties.recordDays();
        if (days <= 0) {
            return new int[]{0, 0};
        }
        LocalDateTime cutoff = now.minusDays(days);
        // The query joins tenants: legal-hold workspaces and archived
        // workspaces are never record-purge candidates.
        java.util.List<BizAppRecord> candidates = recordRepository.findPurgeCandidates(
                cutoff, properties.maxRecordsPerRun());
        int eligible = candidates.size();
        if (!properties.recordPurgeEnabled()) {
            if (eligible > 0) {
                log.warn("Retention: {} BizApp record(s) archived before {} are purge-eligible; "
                        + "hard delete is disabled (app.retention.bizapp-record-purge-enabled=false)",
                        eligible, cutoff);
            }
            return new int[]{eligible, 0};
        }
        int purged = 0;
        for (BizAppRecord record : candidates) {
            jobRepository.deleteByRecordId(record.getId());
            recordRepository.delete(record);
            purged++;
            eventPublisher.publishEvent(AuditLogEvent.builder(this)
                    .tenantId(record.getTenantId())
                    .action(LogAction.BIZAPP_RECORD_PURGED)
                    .resourceType("bizapp_record")
                    .resourceId(record.getId())
                    .metadata(Map.of(
                            "app", record.getAppKind(),
                            "entity", record.getEntity(),
                            "archivedAt", String.valueOf(record.getArchivedAt()),
                            "retentionDays", days))
                    .build());
        }
        return new int[]{eligible, purged};
    }
}
