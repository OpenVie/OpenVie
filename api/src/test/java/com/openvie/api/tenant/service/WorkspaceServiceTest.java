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

package com.openvie.api.tenant.service;

import com.openvie.api.common.cache.BusinessCacheInvalidationPublisher;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.event.AuditLogEvent;
import com.openvie.api.common.retention.RetentionProperties;
import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.api.TenantIdentityApi;
import com.openvie.api.tenant.api.TenantStatus;
import com.openvie.api.tenant.model.Organization;
import com.openvie.api.tenant.model.Tenant;
import com.openvie.api.tenant.repository.OrganizationRepository;
import com.openvie.api.tenant.repository.TenantRepository;
import com.openvie.api.tenant.repository.UserRepository;
import com.openvie.api.tenant.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceServiceTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private OrganizationRepository organizationRepository;
    private TenantRepository workspaceRepository;
    private UserRepository userRepository;
    private WorkspaceMemberRepository memberRepository;
    private TenantIdentityApi identityApi;
    private ApplicationEventPublisher eventPublisher;
    private BusinessCacheInvalidationPublisher cacheInvalidation;
    private WorkspaceService service;
    private Tenant workspace;

    @BeforeEach
    void setUp() {
        organizationRepository = mock(OrganizationRepository.class);
        workspaceRepository = mock(TenantRepository.class);
        userRepository = mock(UserRepository.class);
        memberRepository = mock(WorkspaceMemberRepository.class);
        identityApi = mock(TenantIdentityApi.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        cacheInvalidation = mock(BusinessCacheInvalidationPublisher.class);
        RetentionProperties retention = mock(RetentionProperties.class);
        when(retention.workspaceDays()).thenReturn(30);
        when(retention.purgeEligibleAt(any(), eq(30)))
                .thenAnswer(invocation -> {
                    LocalDateTime archivedAt = invocation.getArgument(0);
                    return archivedAt == null ? null : archivedAt.plusDays(30);
                });

        service = new WorkspaceService(
                organizationRepository, workspaceRepository, userRepository, memberRepository,
                mock(TenantWorkspaceService.class), identityApi, eventPublisher, cacheInvalidation,
                retention);

        Organization organization = new Organization();
        organization.setId(orgId);
        organization.setName("Acme");
        organization.setSlug("acme");

        workspace = new Tenant();
        workspace.setId(workspaceId);
        workspace.setOrganization(organization);
        workspace.setName("Finance");
        workspace.setSlug("finance");
        workspace.setStatus(TenantStatus.ARCHIVED);
        workspace.setArchivedAt(LocalDateTime.now().minusDays(40));

        when(identityApi.findUserById(actorId)).thenReturn(new TenantIdentityApi.IdentitySnapshot(
                actorId, orgId, "owner@example.com", "Owner", OrgRole.ORG_OWNER, "ACTIVE", false));
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
        when(workspaceRepository.save(any(Tenant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(memberRepository.findByWorkspace_IdOrderByRoleAscCreatedAtAsc(workspaceId))
                .thenReturn(List.of());
    }

    @Test
    void restoresAnArchivedWorkspaceAndClearsArchiveMetadata() {
        WorkspaceService.WorkspaceView view = service.restore(actorId, workspaceId);

        assertEquals("ACTIVE", view.getStatus());
        assertEquals(TenantStatus.ACTIVE, workspace.getStatus());
        assertNull(workspace.getArchivedAt());
        assertNull(workspace.getArchivedBy());
        verify(workspaceRepository).save(workspace);
        verify(cacheInvalidation).workspace(workspaceId);

        ArgumentCaptor<AuditLogEvent> audit = ArgumentCaptor.forClass(AuditLogEvent.class);
        verify(eventPublisher).publishEvent(audit.capture());
        assertEquals(LogAction.WORKSPACE_RESTORED, audit.getValue().getAction());
    }

    @Test
    void restoringAnActiveWorkspaceIsIdempotent() {
        workspace.setStatus(TenantStatus.ACTIVE);
        workspace.setArchivedAt(null);

        WorkspaceService.WorkspaceView view = service.restore(actorId, workspaceId);

        assertEquals("ACTIVE", view.getStatus());
        verify(workspaceRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void archiveStoresMetadataAndInvalidatesTheWorkspaceCache() {
        workspace.setStatus(TenantStatus.ACTIVE);
        workspace.setArchivedAt(null);

        service.archive(actorId, workspaceId);

        assertEquals(TenantStatus.ARCHIVED, workspace.getStatus());
        assertEquals(actorId, workspace.getArchivedBy());
        verify(cacheInvalidation).workspace(workspaceId);
        verify(workspaceRepository).save(workspace);
    }

    @Test
    void legalHoldAlwaysAppliesAndIsAudited() {
        WorkspaceService.WorkspaceView view = service.setLegalHold(actorId, workspaceId, true);

        assertEquals(true, view.isLegalHold());
        assertEquals(true, workspace.isLegalHold());
        ArgumentCaptor<AuditLogEvent> audit = ArgumentCaptor.forClass(AuditLogEvent.class);
        verify(eventPublisher).publishEvent(audit.capture());
        assertEquals(LogAction.WORKSPACE_LEGAL_HOLD_CHANGED, audit.getValue().getAction());
    }
}
