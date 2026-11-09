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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openvie.api.bizapp.BizAppConnectorProperties;
import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.connector.BizAppConnectorExecutor;
import com.openvie.api.bizapp.connector.BizAppConnectorSpecParser;
import com.openvie.api.bizapp.connector.ConnectorException;
import com.openvie.api.bizapp.connector.ConnectorOutcome;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Localized;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppConnectorJob;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppConnectorJobRepository;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.bizapp.repository.BizAppRecordRepository;
import com.openvie.api.testsupport.InlineTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class BizAppConnectorWorkerTest {

    private BizAppConnectorExecutor executor;
    private BizAppConnectorJobRepository jobRepository;
    private BizAppInstallationRepository installationRepository;
    private BizAppRecordRepository recordRepository;
    private BizAppManifestParser manifestParser;
    private BizAppConnectorJobService jobService;
    private BizAppConnectorWorker worker;

    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID installationId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private static final Manifest MANIFEST = new Manifest(
            "bizapp.v1", "ot-telemetry", new Localized("OT", "OT"), 1, "cpu",
            Map.of("read", List.of("MEMBER")),
            List.of(new Entity("devices", new Localized("Devices", "Devices"), List.of(
                    new Field("name", "string", false, 100, null, null)), null, Map.of())),
            List.of(new Block("readTemp", "external_call", Map.of(
                    "url", "https://ot-gateway.test/temp",
                    "credentialRef", "gateway-key"))),
            null);

    @BeforeEach
    void setUp() {
        executor = mock(BizAppConnectorExecutor.class);
        jobRepository = mock(BizAppConnectorJobRepository.class);
        installationRepository = mock(BizAppInstallationRepository.class);
        recordRepository = mock(BizAppRecordRepository.class);
        manifestParser = mock(BizAppManifestParser.class);
        when(manifestParser.parse(any())).thenReturn(MANIFEST);
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        jobService = new BizAppConnectorJobService(jobRepository, installationRepository,
                manifestParser, new BizAppConnectorSpecParser(), InlineTransactions.create());
        worker = new BizAppConnectorWorker(
                new BizAppProperties(true, false, false, false, ""),
                new BizAppConnectorProperties(true, 15_000, 5_000, 20),
                jobService, executor, jobRepository, installationRepository, recordRepository,
                manifestParser, InlineTransactions.create());
    }

    private BizAppConnectorJob pendingJob(int attempt, int maxAttempts) {
        BizAppConnectorJob job = new BizAppConnectorJob();
        job.setId(jobId);
        job.setOrgId(orgId);
        job.setTenantId(tenantId);
        job.setInstallationId(installationId);
        job.setAppKind("ot-telemetry");
        job.setEntity("devices");
        job.setRecordId(recordId);
        job.setBlockId("readTemp");
        job.setTriggerKind("HOOK");
        job.setStatus("PENDING");
        job.setAttempt(attempt);
        job.setMaxAttempts(maxAttempts);
        job.setBackoffMs(10_000L);
        job.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        return job;
    }

    private void stubClaim(BizAppConnectorJob job) {
        when(jobRepository.claimDueIds(20)).thenReturn(List.of(job.getId()));
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
    }

    private void stubInstallation(boolean active) {
        BizAppInstallation installation = new BizAppInstallation();
        installation.setId(installationId);
        installation.setOrgId(orgId);
        installation.setTenantId(tenantId);
        installation.setKind("ot-telemetry");
        installation.setManifest(Map.of());
        installation.setStatus(active ? "ACTIVE" : "DISABLED");
        installation.setInstalledBy(actorId);
        when(installationRepository.findById(installationId)).thenReturn(Optional.of(installation));
    }

    private void stubRecord(String status) {
        BizAppRecord record = new BizAppRecord();
        record.setId(recordId);
        record.setOrgId(orgId);
        record.setTenantId(tenantId);
        record.setAppKind("ot-telemetry");
        record.setEntity("devices");
        record.setPayload(Map.of("name", "Boiler 1"));
        record.setStatus(status);
        record.setCreatedBy(actorId);
        record.setUpdatedBy(actorId);
        when(recordRepository.findById(recordId)).thenReturn(Optional.of(record));
    }

    @Test
    void runsAClaimedJobToSuccess() {
        BizAppConnectorJob job = pendingJob(0, 3);
        stubClaim(job);
        stubInstallation(true);
        stubRecord("ACTIVE");
        when(executor.execute(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            assertEquals("RUNNING", job.getStatus(), "claimed jobs are RUNNING while executed");
            return new ConnectorOutcome("wrote sink");
        });

        worker.processDueJobs();

        assertEquals("SUCCEEDED", job.getStatus());
        verify(executor).execute(any(), any(), eq(MANIFEST.blocks().get(0)), any(), eq(actorId),
                eq(jobId));
    }

    @Test
    void retryableFailureKeepsTheJobPendingWithBackoff() {
        BizAppConnectorJob job = pendingJob(0, 3);
        stubClaim(job);
        stubInstallation(true);
        stubRecord("ACTIVE");
        when(executor.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(ConnectorException.retryable("HTTP 503"));

        LocalDateTime before = LocalDateTime.now();
        worker.processDueJobs();

        assertEquals("PENDING", job.getStatus());
        assertEquals(1, job.getAttempt());
        assertEquals("HTTP 503", job.getLastError());
        assertNotNull(job.getNextAttemptAt());
        assertEquals(true, job.getNextAttemptAt().isAfter(before.plusNanos(9_000_000_000L)));
    }

    @Test
    void spentBudgetMarksTheJobDead() {
        BizAppConnectorJob job = pendingJob(2, 3);
        stubClaim(job);
        stubInstallation(true);
        stubRecord("ACTIVE");
        when(executor.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(ConnectorException.retryable("HTTP 503"));

        worker.processDueJobs();

        assertEquals("DEAD", job.getStatus());
        assertEquals(3, job.getAttempt());
    }

    @Test
    void missingManifestBlockKillsTheJob() {
        BizAppConnectorJob job = pendingJob(0, 3);
        job.setBlockId("removedBlock");
        stubClaim(job);
        stubInstallation(true);
        stubRecord("ACTIVE");
        when(manifestParser.parse(any())).thenReturn(new Manifest(
                "bizapp.v1", "ot-telemetry", new Localized("OT", "OT"), 1, "cpu",
                Map.of("read", List.of("MEMBER")),
                List.of(new Entity("devices", new Localized("Devices", "Devices"), List.of(
                        new Field("name", "string", false, 100, null, null)), null, Map.of())),
                List.of(), null));

        worker.processDueJobs();

        assertEquals("DEAD", job.getStatus());
        verify(executor, never()).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingRecordKillsTheJob() {
        BizAppConnectorJob job = pendingJob(0, 3);
        stubClaim(job);
        stubInstallation(true);
        when(recordRepository.findById(recordId)).thenReturn(Optional.empty());

        worker.processDueJobs();

        assertEquals("DEAD", job.getStatus());
        verify(executor, never()).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    void inactiveInstallationKillsTheJob() {
        BizAppConnectorJob job = pendingJob(0, 3);
        stubClaim(job);
        stubInstallation(false);
        stubRecord("ACTIVE");

        worker.processDueJobs();

        assertEquals("DEAD", job.getStatus());
        verify(executor, never()).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    void disabledConnectorsDoNotClaimJobs() {
        worker = new BizAppConnectorWorker(
                new BizAppProperties(true, false, false, false, ""),
                new BizAppConnectorProperties(false, 15_000, 5_000, 20),
                jobService, executor, jobRepository, installationRepository, recordRepository,
                manifestParser, InlineTransactions.create());

        worker.processDueJobs();

        verify(jobRepository, never()).claimDueIds(anyInt());
    }

    @Test
    void claimFailuresNeverEscapeTheScheduledMethod() {
        when(jobRepository.claimDueIds(anyInt()))
                .thenThrow(new IllegalStateException("database down"));

        worker.processDueJobs();
    }

    @Test
    void unexpectedExecutorFailuresAreRetriedAndAuditedByTheJob() {
        BizAppConnectorJob job = pendingJob(0, 3);
        stubClaim(job);
        stubInstallation(true);
        stubRecord("ACTIVE");
        when(executor.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        worker.processDueJobs();

        assertEquals("PENDING", job.getStatus());
        assertEquals(1, job.getAttempt());
        assertEquals("boom", job.getLastError());
    }
}
