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
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.tenant.api.TenantWorkspaceApi;
import com.openvie.api.workflow.api.WorkflowRuntimeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RevisionScopeTest {

    private BizAppInstallationRepository installationRepository;
    private BizAppManifestValidator validator;
    private BizAppManifestParser parser;
    private WorkflowRuntimeApi workflowRuntimeApi;
    private BizAppRuntime runtime;
    private BizAppProperties properties;
    private TenantWorkspaceApi tenantWorkspaceApi;

    private BizAppInstallService installService;

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
        tenantWorkspaceApi = mock(TenantWorkspaceApi.class);

        when(properties.enabled()).thenReturn(true);

        installService = new BizAppInstallService(
                installationRepository,
                validator,
                parser,
                workflowRuntimeApi,
                runtime,
                properties
        );
    }

    @Test
    void installAndUninstallDoNotIncrementSearchRevision() {
        BizAppInstallation installation = new BizAppInstallation();
        installation.setId(UUID.randomUUID());
        installation.setTenantId(tenantId);
        installation.setKind("crm");
        installation.setStatus("ACTIVE");

        when(installationRepository.findByTenantIdAndKind(tenantId, "crm"))
                .thenReturn(Optional.of(installation));

        installService.uninstall(tenantId, actorId, "crm");

        verify(installationRepository).save(installation);
        verify(tenantWorkspaceApi, never()).incrementSearchRevision(any(), any());
    }
}
