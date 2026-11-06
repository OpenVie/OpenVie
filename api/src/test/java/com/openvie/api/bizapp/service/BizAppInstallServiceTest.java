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

import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.workflow.api.WorkflowRuntimeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BizAppInstallServiceTest {

    private BizAppInstallationRepository installationRepository;
    private BizAppManifestValidator validator;
    private BizAppManifestParser parser;
    private WorkflowRuntimeApi workflowRuntimeApi;
    private BizAppRuntime runtime;
    private BizAppProperties properties;
    private BizAppInstallService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        installationRepository = mock(BizAppInstallationRepository.class);
        validator = mock(BizAppManifestValidator.class);
        parser = mock(BizAppManifestParser.class);
        workflowRuntimeApi = mock(WorkflowRuntimeApi.class);
        runtime = mock(BizAppRuntime.class);
        properties = mock(BizAppProperties.class);

        when(properties.enabled()).thenReturn(true);

        service = new BizAppInstallService(
                installationRepository, validator, parser, workflowRuntimeApi, runtime, properties
        );
    }

    private Manifest createMockManifest(String kind, int version) {
        return new Manifest(
                "bizapp.v1",
                kind,
                new com.openvie.api.bizapp.dto.BizAppManifest.Localized(kind, kind),
                version,
                "icon",
                "postgres",
                Map.of(),
                List.of(),
                List.of(),
                null
        );
    }

    @Test
    void installNewAppCreatesActiveInstallation() {
        Map<String, Object> rawManifest = Map.of("kind", "booking", "version", 1);
        Manifest manifest = createMockManifest("booking", 1);
        when(parser.parse(rawManifest)).thenReturn(manifest);
        when(installationRepository.findByTenantIdAndKind(tenantId, "booking")).thenReturn(Optional.empty());

        when(installationRepository.save(any(BizAppInstallation.class))).thenAnswer(inv -> {
            BizAppInstallation inst = inv.getArgument(0);
            inst.setId(UUID.randomUUID());
            return inst;
        });

        var result = service.install(tenantId, orgId, actorId, rawManifest);

        assertNotNull(result);
        assertEquals("booking", result.kind());
        assertEquals(1, result.version());

        ArgumentCaptor<BizAppInstallation> captor = ArgumentCaptor.forClass(BizAppInstallation.class);
        verify(installationRepository).save(captor.capture());
        BizAppInstallation saved = captor.getValue();
        assertEquals("ACTIVE", saved.getStatus());
        assertEquals(tenantId, saved.getTenantId());
        assertEquals(orgId, saved.getOrgId());
        assertEquals(actorId, saved.getInstalledBy());
    }

    @Test
    void installAlreadyActiveAppThrowsConflictException() {
        Map<String, Object> rawManifest = Map.of("kind", "booking", "version", 1);
        Manifest manifest = createMockManifest("booking", 1);
        when(parser.parse(rawManifest)).thenReturn(manifest);

        BizAppInstallation existing = new BizAppInstallation();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        existing.setKind("booking");
        existing.setStatus("ACTIVE");

        when(installationRepository.findByTenantIdAndKind(tenantId, "booking")).thenReturn(Optional.of(existing));

        assertThrows(ConflictException.class, () ->
                service.install(tenantId, orgId, actorId, rawManifest));

        verify(installationRepository, never()).save(any());
    }

    @Test
    void installPreviouslyDisabledAppReactivatesExistingRow() {
        Map<String, Object> rawManifest = Map.of("kind", "booking", "version", 2);
        Manifest manifest = createMockManifest("booking", 2);
        when(parser.parse(rawManifest)).thenReturn(manifest);

        UUID existingId = UUID.randomUUID();
        BizAppInstallation disabled = new BizAppInstallation();
        disabled.setId(existingId);
        disabled.setTenantId(tenantId);
        disabled.setOrgId(orgId);
        disabled.setKind("booking");
        disabled.setStatus("DISABLED");

        when(installationRepository.findByTenantIdAndKind(tenantId, "booking")).thenReturn(Optional.of(disabled));
        when(installationRepository.save(any(BizAppInstallation.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.install(tenantId, orgId, actorId, rawManifest);

        assertNotNull(result);
        assertEquals("booking", result.kind());
        assertEquals(2, result.version());

        ArgumentCaptor<BizAppInstallation> captor = ArgumentCaptor.forClass(BizAppInstallation.class);
        verify(installationRepository).save(captor.capture());
        BizAppInstallation saved = captor.getValue();
        assertEquals(existingId, saved.getId(), "Must update the same row instead of inserting a duplicate");
        assertEquals("ACTIVE", saved.getStatus());
        assertEquals(2, saved.getVersion());
        assertEquals(actorId, saved.getInstalledBy());
    }

    @Test
    void uninstallMarksInstallationDisabled() {
        BizAppInstallation active = new BizAppInstallation();
        active.setId(UUID.randomUUID());
        active.setTenantId(tenantId);
        active.setKind("booking");
        active.setStatus("ACTIVE");

        when(installationRepository.findByTenantIdAndKind(tenantId, "booking")).thenReturn(Optional.of(active));

        service.uninstall(tenantId, actorId, "booking");

        assertEquals("DISABLED", active.getStatus());
        verify(installationRepository).save(active);
    }

    @Test
    void installFallsBackToGenericApprovalProcessWhenBpmnTemplateUnbundled() {
        // Chat-drafted manifests may point approval_workitem at a bespoke BPMN
        // file the platform does not bundle (a hallucinated asset path). The
        // install must degrade to the bundled generic single-approval process
        // instead of failing with "BPMN template not available".
        Map<String, Object> rawManifest = Map.of("kind", "leave-management", "version", 1);
        Manifest manifest = new Manifest(
                "bizapp.v1",
                "leave-management",
                new com.openvie.api.bizapp.dto.BizAppManifest.Localized("Quản lý nghỉ phép", "Leave Management"),
                1,
                "icon",
                "jsonb",
                Map.of(),
                List.of(),
                List.of(new com.openvie.api.bizapp.dto.BizAppManifest.Block(
                        "approval1", "approval_workitem",
                        Map.of(
                                "bpmnTemplate", "processes/leave-management-single-approval.bpmn20.xml",
                                "candidateRole", "WORKSPACE_ADMIN"))),
                null
        );
        when(parser.parse(rawManifest)).thenReturn(manifest);
        when(installationRepository.findByTenantIdAndKind(tenantId, "leave-management")).thenReturn(Optional.empty());
        when(installationRepository.save(any(BizAppInstallation.class))).thenAnswer(inv -> {
            BizAppInstallation inst = inv.getArgument(0);
            inst.setId(UUID.randomUUID());
            return inst;
        });
        when(workflowRuntimeApi.deploy(eq(tenantId), eq(actorId), eq("bizapp-leave-management"), anyString()))
                .thenReturn(new WorkflowRuntimeApi.DeployedDefinition("dep1", "leaveManagement", "Leave approval", 1));

        var result = service.install(tenantId, orgId, actorId, rawManifest);

        assertNotNull(result);
        assertEquals("leave-management", result.kind());
        ArgumentCaptor<String> xmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(workflowRuntimeApi).deploy(eq(tenantId), eq(actorId), eq("bizapp-leave-management"), xmlCaptor.capture());
        assertTrue(xmlCaptor.getValue().contains("bizappSingleApproval"),
                "unbundled BPMN template paths must fall back to the bundled generic approval process");
    }
}
