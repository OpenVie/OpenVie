package com.openvie.api.tenant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.event.AuditLogEvent;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.common.exception.custom.UnauthorizedException;
import com.openvie.api.tenant.api.DeliveryAvailability;
import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.api.TenantCredentials;
import com.openvie.api.tenant.api.TenantStatus;
import com.openvie.api.tenant.api.UserStatus;
import com.openvie.api.tenant.api.WorkspaceRole;
import com.openvie.api.tenant.enums.WorkspaceVisibility;
import com.openvie.api.tenant.model.Organization;
import com.openvie.api.tenant.model.Tenant;
import com.openvie.api.tenant.model.User;
import com.openvie.api.tenant.model.WorkspaceMember;
import com.openvie.api.tenant.repository.InvitationRepository;
import com.openvie.api.tenant.repository.OrganizationRepository;
import com.openvie.api.tenant.repository.TenantRepository;
import com.openvie.api.tenant.repository.UserRepository;
import com.openvie.api.tenant.repository.WorkspaceMemberRepository;
import com.openvie.api.tenant.api.TenantIdentityApi.SsoLinkResult;

/**
 * Pre-SSO password accounts link to a Keycloak identity by verified
 * email. Linking never changes roles or memberships, so workspace
 * authorization (including {@code recover-owner}) keeps working unchanged.
 */
class SsoLinkTest {

    private UserRepository users;
    private WorkspaceMemberRepository members;
    private ApplicationEventPublisher events;
    private TenantUserManagementService service;

    private Organization organization;
    private Tenant workspace;
    private User member;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        members = mock(WorkspaceMemberRepository.class);
        TenantRepository workspaces = mock(TenantRepository.class);
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        DeliveryAvailability channels = mock(DeliveryAvailability.class);
        TenantCredentials credentials = mock(TenantCredentials.class);
        events = mock(ApplicationEventPublisher.class);
        service = new TenantUserManagementService(
                users, mock(InvitationRepository.class), members, workspaces,
                organizations, channels, credentials, events);

        organization = new Organization();
        organization.setId(UUID.randomUUID());

        workspace = new Tenant();
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(organization);

        member = new User();
        member.setId(UUID.randomUUID());
        member.setOrganization(organization);
        member.setEmail("member@example.com");
        member.setRole(OrgRole.MEMBER);
        member.setStatus(UserStatus.ACTIVE);

        when(users.findByKeycloakSubject("kc-sub-1")).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("member@example.com")).thenReturn(Optional.of(member));
        when(members.findVisibleByUserId(member.getId())).thenReturn(List.of(membership()));
    }

    @Test
    void autoLinksByVerifiedEmailKeepingRoleAndMemberships() {
        SsoLinkResult result = service.linkSsoIdentity(
                organization.getId(), "kc-sub-1", "member@example.com", true);

        assertTrue(result.newlyLinked());
        assertEquals(member.getId(), result.userId());
        assertEquals("kc-sub-1", member.getKeycloakSubject());
        assertEquals(OrgRole.MEMBER, member.getRole());
        verify(users).save(member);
        verify(events).publishEvent(any(AuditLogEvent.class));
    }

    @Test
    void alreadyLinkedSubjectIsIdempotent() {
        member.setKeycloakSubject("kc-sub-1");
        when(users.findByKeycloakSubject("kc-sub-1")).thenReturn(Optional.of(member));

        SsoLinkResult result = service.linkSsoIdentity(
                organization.getId(), "kc-sub-1", "member@example.com", true);

        assertFalse(result.newlyLinked());
        assertEquals(member.getId(), result.userId());
        verify(users, never()).save(any());
    }

    @Test
    void subjectLinkedToAnotherOrgIsRejected() {
        Organization other = new Organization();
        other.setId(UUID.randomUUID());
        member.setOrganization(other);
        member.setKeycloakSubject("kc-sub-1");
        when(users.findByKeycloakSubject("kc-sub-1")).thenReturn(Optional.of(member));

        assertThrows(ConflictException.class, () -> service.linkSsoIdentity(
                organization.getId(), "kc-sub-1", "member@example.com", true));
    }

    @Test
    void unverifiedEmailIsRejected() {
        ConflictException exception = assertThrows(ConflictException.class, () -> service.linkSsoIdentity(
                organization.getId(), "kc-sub-1", "member@example.com", false));

        assertTrue(exception.getMessage().contains("verified email"));
        verify(users, never()).save(any());
    }

    @Test
    void unknownEmailIsRejectedWithoutProvisioning() {
        when(users.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> service.linkSsoIdentity(organization.getId(), "kc-sub-9", "ghost@example.com", true));

        assertTrue(exception.getMessage().contains("SSO_UNKNOWN_EMAIL"));
        verify(users, never()).save(any());
    }

    @Test
    void blankSubjectIsRejected() {
        assertThrows(BadRequestException.class, () -> service.linkSsoIdentity(
                organization.getId(), "  ", "member@example.com", true));
    }

    @Test
    void disabledAccountIsRejected() {
        member.setStatus(UserStatus.INACTIVE);

        assertThrows(UnauthorizedException.class, () -> service.linkSsoIdentity(
                organization.getId(), "kc-sub-1", "member@example.com", true));
        verify(users, never()).save(any());
    }

    @Test
    void ownerKeepsRoleAfterLink() {
        member.setRole(OrgRole.ORG_OWNER);

        SsoLinkResult result = service.linkSsoIdentity(
                organization.getId(), "kc-sub-1", "member@example.com", true);

        assertTrue(result.newlyLinked());
        assertEquals(OrgRole.ORG_OWNER, member.getRole());
    }

    @Test
    void auditTrailCarriesSsoLinkAction() {
        var captor = org.mockito.ArgumentCaptor.forClass(AuditLogEvent.class);
        service.linkSsoIdentity(organization.getId(), "kc-sub-1", "member@example.com", true);

        verify(events).publishEvent(captor.capture());
        assertEquals(LogAction.USER_SSO_LINKED, captor.getValue().getAction());
    }

    private WorkspaceMember membership() {
        WorkspaceMember membership = new WorkspaceMember();
        membership.setId(UUID.randomUUID());
        membership.setUser(member);
        membership.setWorkspace(workspace);
        membership.setRole(WorkspaceRole.MEMBER);
        membership.setCreatedAt(LocalDateTime.now());
        return membership;
    }
}
