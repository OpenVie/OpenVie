package com.cacanode.api.tenant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.cacanode.api.common.cache.CacheKeyFactory;
import com.cacanode.api.common.cache.CacheMetrics;
import com.cacanode.api.common.cache.CacheOperationStatus;
import com.cacanode.api.common.cache.CacheReadResult;
import com.cacanode.api.common.cache.CacheReadStatus;
import com.cacanode.api.tenant.api.TenantCredentials;
import com.cacanode.api.common.cache.CacheStore;
import com.cacanode.api.common.cache.VersionedJsonCache;
import com.cacanode.api.common.config.CacheProperties;
import com.cacanode.api.tenant.api.DeliveryAvailability;
import com.cacanode.api.tenant.api.InvitationStatus;
import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.TenantStatus;
import com.cacanode.api.tenant.api.UserStatus;
import com.cacanode.api.tenant.api.WorkspaceRole;
import com.cacanode.api.tenant.enums.WorkspaceVisibility;
import com.cacanode.api.tenant.model.Invitation;
import com.cacanode.api.tenant.model.Organization;
import com.cacanode.api.tenant.model.Tenant;
import com.cacanode.api.tenant.model.User;
import com.cacanode.api.tenant.model.WorkspaceMember;
import com.cacanode.api.tenant.repository.InvitationRepository;
import com.cacanode.api.tenant.repository.TenantRepository;
import com.cacanode.api.tenant.repository.UserRepository;
import com.cacanode.api.tenant.repository.WorkspaceMemberRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The directory snapshot is cached per workspace, while the "is this me"
 * decoration and invitation expiry are recomputed on every request.
 */
class Phase2DirectoryCacheTest {

    @Test
    void cachedWorkspaceSnapshotDecoratesViewerAndExpiryPerRequest() {
        Organization organization = organization();
        Tenant workspace = workspace(organization);
        UUID workspaceId = workspace.getId();

        User first = member(organization, "first@example.com");
        User second = member(organization, "second@example.com");

        Invitation invitation = new Invitation();
        invitation.setId(UUID.randomUUID());
        invitation.setTenant(workspace);
        invitation.setInvitedBy(first);
        invitation.setEmail("pending@example.com");
        invitation.setRole(WorkspaceRole.MEMBER);
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setCreatedAt(LocalDateTime.now().minusDays(4));
        invitation.setLastSentAt(LocalDateTime.now().minusDays(4));
        invitation.setExpiresAt(LocalDateTime.now().minusSeconds(1));

        UserRepository users = mock(UserRepository.class);
        InvitationRepository invitations = mock(InvitationRepository.class);
        WorkspaceMemberRepository members = mock(WorkspaceMemberRepository.class);
        TenantRepository workspaces = mock(TenantRepository.class);

        when(users.findById(first.getId())).thenReturn(Optional.of(first));
        when(users.findById(second.getId())).thenReturn(Optional.of(second));
        when(members.findByUser_IdAndWorkspace_Id(any(), any())).thenAnswer(call -> {
            UUID userId = call.getArgument(0);
            User user = userId.equals(first.getId()) ? first : second;
            return Optional.of(membership(user, workspace, WorkspaceRole.WORKSPACE_ADMIN));
        });
        when(members.findByWorkspace_IdOrderByRoleAscCreatedAtAsc(workspaceId))
                .thenReturn(List.of(
                        membership(first, workspace, WorkspaceRole.WORKSPACE_ADMIN),
                        membership(second, workspace, WorkspaceRole.MEMBER)));
        when(invitations.findByTenant_IdOrderByCreatedAtDesc(workspaceId))
                .thenReturn(List.of(invitation));
        when(workspaces.findById(workspaceId)).thenReturn(Optional.of(workspace));

        TenantUserManagementService service = new TenantUserManagementService(
                users, invitations, members, workspaces,
                mock(DeliveryAvailability.class), mock(TenantCredentials.class),
                mock(ApplicationEventPublisher.class));
        CacheProperties properties = new CacheProperties();
        properties.setEnabled(true);
        properties.setBusinessReadEnabled(true);
        properties.setUserDirectoryEnabled(true);
        ReflectionTestUtils.setField(service, "businessCache", new VersionedJsonCache(
                new MemoryStore(), properties, mock(CacheMetrics.class),
                new ObjectMapper().findAndRegisterModules()));
        ReflectionTestUtils.setField(service, "cacheKeyFactory", new CacheKeyFactory("ccn:v2"));

        var firstView = service.getDirectory(workspaceId, first.getId());
        var secondView = service.getDirectory(workspaceId, second.getId());

        // Same cached snapshot, different per-viewer decoration.
        assertTrue(firstView.members().getFirst().currentUser());
        assertFalse(firstView.members().get(1).currentUser());
        assertFalse(secondView.members().getFirst().currentUser());
        assertTrue(secondView.members().get(1).currentUser());
        // The stale invitation is reported expired for both viewers.
        assertEquals(InvitationStatus.EXPIRED, firstView.invitations().getFirst().status());
        assertEquals(InvitationStatus.EXPIRED, secondView.invitations().getFirst().status());

        // The second viewer was served from cache: the workspace loaded once.
        verify(members, times(1)).findByWorkspace_IdOrderByRoleAscCreatedAtAsc(workspaceId);
        verify(invitations, times(1)).findByTenant_IdOrderByCreatedAtDesc(workspaceId);
    }

    private static Organization organization() {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName("Acme Corp");
        organization.setSlug("acme-corp");
        organization.setCreatedAt(LocalDateTime.now());
        return organization;
    }

    private static Tenant workspace(Organization organization) {
        Tenant workspace = new Tenant();
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(organization);
        workspace.setName("General");
        workspace.setSlug("general");
        workspace.setStatus(TenantStatus.ACTIVE);
        workspace.setVisibility(WorkspaceVisibility.PUBLIC);
        workspace.setDefaultWorkspace(true);
        workspace.setCreatedAt(LocalDateTime.now());
        return workspace;
    }

    private static User member(Organization organization, String email) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setOrganization(organization);
        user.setEmail(email);
        user.setFullName(email);
        user.setPasswordHash("hash");
        user.setRole(OrgRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(LocalDateTime.now().minusDays(1));
        return user;
    }

    private static WorkspaceMember membership(
            User user, Tenant workspace, WorkspaceRole role) {
        WorkspaceMember membership = new WorkspaceMember();
        membership.setId(UUID.randomUUID());
        membership.setUser(user);
        membership.setWorkspace(workspace);
        membership.setRole(role);
        membership.setCreatedAt(LocalDateTime.now());
        return membership;
    }

    private static final class MemoryStore implements CacheStore {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override
        public CacheReadResult get(String cacheName, String key) {
            byte[] value = values.get(key);
            return value == null
                    ? CacheReadResult.of(CacheReadStatus.MISS)
                    : CacheReadResult.hit(value);
        }

        @Override
        public CacheOperationStatus put(String cacheName, String key, byte[] value, Duration ttl) {
            values.put(key, value.clone());
            return CacheOperationStatus.SUCCESS;
        }

        @Override
        public CacheOperationStatus delete(String cacheName, String key) {
            values.remove(key);
            return CacheOperationStatus.SUCCESS;
        }
    }
}
