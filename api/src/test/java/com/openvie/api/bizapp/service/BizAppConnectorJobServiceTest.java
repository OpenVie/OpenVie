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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openvie.api.bizapp.connector.BizAppConnectorSpecParser;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Localized;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppConnectorJob;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.repository.BizAppConnectorJobRepository;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.testsupport.InlineTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class BizAppConnectorJobServiceTest {

    private BizAppConnectorJobRepository jobRepository;
    private BizAppInstallationRepository installationRepository;
    private BizAppManifestParser manifestParser;
    private BizAppConnectorJobService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID installationId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    private static final Manifest MANIFEST = new Manifest(
            "bizapp.v1", "ot-telemetry", new Localized("OT", "OT"), 1, "cpu",
            Map.of("read", List.of("MEMBER")),
            List.of(new Entity("devices", new Localized("Devices", "Devices"), List.of(
                    new Field("name", "string", false, 100, null, null)), null, Map.of())),
            List.of(new Block("readTemp", "external_call", Map.of(
                    "url", "https://ot-gateway.test/temp",
                    "credentialRef", "gateway-key",
                    "retry", Map.of("maxAttempts", 2, "backoff", "PT5S")))),
            null);

    @BeforeEach
    void setUp() {
        jobRepository = mock(BizAppConnectorJobRepository.class);
        installationRepository = mock(BizAppInstallationRepository.class);
        manifestParser = mock(BizAppManifestParser.class);
        when(manifestParser.parse(any())).thenReturn(MANIFEST);
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            BizAppConnectorJob job = invocation.getArgument(0);
            if (job.getId() == null) {
                job.setId(UUID.randomUUID());
            }
            return job;
        });
        service = new BizAppConnectorJobService(jobRepository, installationRepository,
                manifestParser, new BizAppConnectorSpecParser(), InlineTransactions.create());
    }

    private BizAppInstallation installation() {
        BizAppInstallation installation = new BizAppInstallation();
        installation.setId(installationId);
        installation.setOrgId(orgId);
        installation.setTenantId(tenantId);
        installation.setKind("ot-telemetry");
        installation.setManifest(Map.of());
        installation.setInstalledBy(actorId);
        return installation;
    }

    @Test
    void enqueueSnapshotsRetryPolicyFromTheDeclaredBlock() {
        when(installationRepository.findByTenantIdAndKindAndStatus(tenantId, "ot-telemetry", "ACTIVE"))
                .thenReturn(Optional.of(installation()));

        UUID jobId = service.enqueue(tenantId, orgId, "ot-telemetry", "devices", recordId,
                "readTemp", actorId, "HOOK");

        assertNotNull(jobId);
        ArgumentCaptor<BizAppConnectorJob> saved = ArgumentCaptor.forClass(BizAppConnectorJob.class);
        verify(jobRepository).save(saved.capture());
        BizAppConnectorJob job = saved.getValue();
        assertEquals("PENDING", job.getStatus());
        assertEquals(0, job.getAttempt());
        assertEquals(2, job.getMaxAttempts());
        assertEquals(5_000L, job.getBackoffMs());
        assertEquals("HOOK", job.getTriggerKind());
        assertEquals(installationId, job.getInstallationId());
        assertEquals(recordId, job.getRecordId());
        assertEquals("readTemp", job.getBlockId());
        assertNotNull(job.getNextAttemptAt());
    }

    @Test
    void enqueueRequiresAnActiveInstallationAndDeclaredBlock() {
        when(installationRepository.findByTenantIdAndKindAndStatus(any(), any(), any()))
                .thenReturn(Optional.empty());
        assertThrows(BadRequestException.class, () -> service.enqueue(tenantId, orgId, "ghost",
                "devices", recordId, "readTemp", actorId, "HOOK"));

        when(installationRepository.findByTenantIdAndKindAndStatus(tenantId, "ot-telemetry", "ACTIVE"))
                .thenReturn(Optional.of(installation()));
        assertThrows(BadRequestException.class, () -> service.enqueue(tenantId, orgId, "ot-telemetry",
                "devices", recordId, "missingBlock", actorId, "HOOK"));
    }

    @Test
    void claimMarksDueJobsRunning() {
        BizAppConnectorJob job = new BizAppConnectorJob();
        job.setId(UUID.randomUUID());
        job.setStatus("PENDING");
        when(jobRepository.claimDueIds(20)).thenReturn(List.of(job.getId()));
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        List<UUID> claimed = service.claim(20);

        assertEquals(List.of(job.getId()), claimed);
        assertEquals("RUNNING", job.getStatus());
    }

    @Test
    void succeedTerminatesTheJob() {
        BizAppConnectorJob job = new BizAppConnectorJob();
        job.setId(UUID.randomUUID());
        job.setStatus("RUNNING");
        job.setLastError("old");
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.succeed(job.getId());

        assertEquals("SUCCEEDED", job.getStatus());
        assertNull(job.getLastError());
    }

    @Test
    void retryableFailureBacksOffExponentially() {
        BizAppConnectorJob job = new BizAppConnectorJob();
        job.setId(UUID.randomUUID());
        job.setStatus("RUNNING");
        job.setAttempt(0);
        job.setMaxAttempts(3);
        job.setBackoffMs(10_000L);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        LocalDateTime before = LocalDateTime.now();

        service.fail(job.getId(), 503, "HTTP 503", true);

        assertEquals("PENDING", job.getStatus());
        assertEquals(1, job.getAttempt());
        assertTrue(job.getNextAttemptAt().isAfter(before.plusNanos(9_000_000_000L)));

        service.fail(job.getId(), 503, "HTTP 503", true);
        assertEquals(2, job.getAttempt());
        assertTrue(job.getNextAttemptAt().isAfter(before.plusNanos(19_000_000_000L)));
    }

    @Test
    void permanentFailureOrSpentBudgetGoesDead() {
        BizAppConnectorJob permanent = new BizAppConnectorJob();
        permanent.setId(UUID.randomUUID());
        permanent.setStatus("RUNNING");
        permanent.setAttempt(0);
        permanent.setMaxAttempts(3);
        when(jobRepository.findById(permanent.getId())).thenReturn(Optional.of(permanent));

        service.fail(permanent.getId(), 404, "HTTP 404", false);
        assertEquals("DEAD", permanent.getStatus());
        assertEquals(1, permanent.getAttempt());

        BizAppConnectorJob exhausted = new BizAppConnectorJob();
        exhausted.setId(UUID.randomUUID());
        exhausted.setStatus("RUNNING");
        exhausted.setAttempt(2);
        exhausted.setMaxAttempts(3);
        when(jobRepository.findById(exhausted.getId())).thenReturn(Optional.of(exhausted));

        service.fail(exhausted.getId(), 503, "HTTP 503", true);
        assertEquals("DEAD", exhausted.getStatus());
        assertEquals(3, exhausted.getAttempt());
    }

    @Test
    void claimOfZeroIsANoOp() {
        assertTrue(service.claim(0).isEmpty());
    }
}
