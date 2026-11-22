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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionSweepServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();

    private TenantRepository tenantRepository;
    private BizAppRecordRepository recordRepository;
    private BizAppConnectorJobRepository jobRepository;
    private ApplicationEventPublisher eventPublisher;
    private RetentionProperties properties;
    private RetentionSweepService service;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        recordRepository = mock(BizAppRecordRepository.class);
        jobRepository = mock(BizAppConnectorJobRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        properties = mock(RetentionProperties.class);
        when(properties.maxRecordsPerRun()).thenReturn(200);
        service = new RetentionSweepService(
                properties, tenantRepository, recordRepository, jobRepository, eventPublisher);
    }

    @Test
    void workspaceCandidatesAreAuditedButNeverDeleted() {
        Tenant workspace = new Tenant();
        workspace.setId(tenantId);
        workspace.setArchivedAt(LocalDateTime.now().minusDays(45));
        when(properties.workspaceDays()).thenReturn(30);
        when(properties.purgeEligibleAt(any(), eq(30)))
                .thenAnswer(invocation -> ((LocalDateTime) invocation.getArgument(0)).plusDays(30));
        when(tenantRepository.findByStatusAndArchivedAtBeforeAndLegalHoldFalse(eq(TenantStatus.ARCHIVED), any(), any()))
                .thenReturn(new PageImpl<>(List.of(workspace)));

        int candidates = service.reportWorkspaceCandidates(LocalDateTime.now());

        assertEquals(1, candidates);
        verify(tenantRepository, never()).delete(any(Tenant.class));
        ArgumentCaptor<AuditLogEvent> audit = ArgumentCaptor.forClass(AuditLogEvent.class);
        verify(eventPublisher).publishEvent(audit.capture());
        assertEquals(LogAction.RETENTION_SWEEP, audit.getValue().getAction());
    }

    @Test
    void recordPurgeIsDisabledByDefaultAndOnlyReports() {
        BizAppRecord record = archivedRecord();
        when(properties.recordDays()).thenReturn(30);
        when(properties.recordPurgeEnabled()).thenReturn(false);
        when(recordRepository.findPurgeCandidates(any(), eq(200)))
                .thenReturn(List.of(record));

        int[] result = service.sweepRecords(LocalDateTime.now());

        assertEquals(1, result[0]);
        assertEquals(0, result[1]);
        verify(recordRepository, never()).delete(any(BizAppRecord.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void recordPurgeDeletesJobsAndAuditsWhenEnabled() {
        BizAppRecord record = archivedRecord();
        when(properties.recordDays()).thenReturn(30);
        when(properties.recordPurgeEnabled()).thenReturn(true);
        when(properties.maxRecordsPerRun()).thenReturn(200);
        when(recordRepository.findPurgeCandidates(any(), eq(200)))
                .thenReturn(List.of(record));

        int[] result = service.sweepRecords(LocalDateTime.now());

        assertEquals(1, result[1]);
        verify(jobRepository).deleteByRecordId(recordId);
        verify(recordRepository).delete(record);
        ArgumentCaptor<AuditLogEvent> audit = ArgumentCaptor.forClass(AuditLogEvent.class);
        verify(eventPublisher).publishEvent(audit.capture());
        assertEquals(LogAction.BIZAPP_RECORD_PURGED, audit.getValue().getAction());
        assertEquals(tenantId, audit.getValue().getTenantId());
    }

    private BizAppRecord archivedRecord() {
        BizAppRecord record = new BizAppRecord();
        record.setId(recordId);
        record.setTenantId(tenantId);
        record.setAppKind("crm");
        record.setEntity("leads");
        record.setStatus("ARCHIVED");
        record.setArchivedAt(LocalDateTime.now().minusDays(45));
        return record;
    }
}
