package com.cacanode.api.tenant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.cacanode.api.common.exception.custom.BadRequestException;
import com.cacanode.api.common.exception.custom.ConflictException;
import com.cacanode.api.common.exception.custom.UnauthorizedException;
import com.cacanode.api.tenant.api.DeliveryAvailability;
import com.cacanode.api.tenant.api.InvitationStatus;
import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.TenantIdentityApi.MembershipSnapshot;
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

/**
 * Workspace-scoped member and invitation rules, including the requirement that
 * an enabled notification channel exists before an invitation is minted.
 */
class TenantUserManagementServiceTest {

    private UserRepository users;
    private InvitationRepository invitations;
    private WorkspaceMemberRepository members;
    private TenantRepository workspaces;
    private DeliveryAvailability channels;
    private ApplicationEventPublisher events;
    private TenantUserManagementService service;

    private Organization organization;
    private Tenant workspace;
    private UUID workspaceId;
    private User admin;
    private User member;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        invitations = mock(InvitationRepository.class);
        members = mock(WorkspaceMemberRepository.class);
        workspaces = mock(TenantRepository.class);
        channels = mock(DeliveryAvailability.class);
        events = mock(ApplicationEventPublisher.class);
        service = new TenantUserManagementService(
                users, invitations, members, workspaces, channels, events);

        organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName("Acme Corp");
        organization.setSlug("acme-corp");

        workspace = new Tenant();
        workspace.setId(UUID.randomUUID());
        workspaceId = workspace.getId();
        workspace.setOrganization(organization);
        workspace.setName("General");
        workspace.setSlug("general");
        workspace.setStatus(TenantStatus.ACTIVE);
        workspace.setVisibility(WorkspaceVisibility.PUBLIC);
        workspace.setDefaultWorkspace(true);
        workspace.setCreatedAt(LocalDateTime.now());

        admin = user("admin@example.com", OrgRole.ORG_OWNER);
        member = user("member@example.com", OrgRole.MEMBER);

        // Both actors are workspace admins with live membership.
        givenMembership(admin, WorkspaceRole.WORKSPACE_ADMIN);
        givenMembership(member, WorkspaceRole.MEMBER);
        when(workspaces.findById(workspaceId)).thenReturn(Optional.of(workspace));
        when(channels.enabledFor(organization.getId())).thenReturn(true);

        when(invitations.save(any())).thenAnswer(call -> {
            Invitation value = call.getArgument(0);
            if (value.getId() == null) value.setId(UUID.randomUUID());
            if (value.getCreatedAt() == null) value.setCreatedAt(LocalDateTime.now());
            return value;
        });
        when(users.save(any())).thenAnswer(call -> {
            User value = call.getArgument(0);
            if (value.getId() == null) value.setId(UUID.randomUUID());
            if (value.getCreatedAt() == null) value.setCreatedAt(LocalDateTime.now());
            return value;
        });
        when(members.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void invitationIsRefusedWithoutAnEnabledChannel() {
        when(channels.enabledFor(organization.getId())).thenReturn(false);

        ConflictException exception = assertThrows(ConflictException.class,
                () -> service.invite(workspaceId, admin.getId(), "new@example.com", WorkspaceRole.MEMBER));

        assertTrue(exception.getMessage().contains("notification channel"));
        verify(invitations, never()).save(any());
    }

    @Test
    void invitationRejectsExistingAccount() {
        when(users.existsByEmailIgnoreCase("person@example.com")).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.invite(
                workspaceId, admin.getId(), " Person@Example.com ", WorkspaceRole.MEMBER));
    }

    @Test
    void invitationExpiresAStalePendingRowAndCreatesAFreshToken() {
        Invitation stale = invitation(InvitationStatus.PENDING);
        stale.setExpiresAt(LocalDateTime.now().minusHours(1));
        when(invitations.findFirstByTenant_IdAndEmailIgnoreCaseAndStatus(
                workspaceId, "person@example.com", InvitationStatus.PENDING))
                .thenReturn(Optional.of(stale));

        var created = service.invite(workspaceId, admin.getId(), "Person@Example.com", WorkspaceRole.MEMBER);

        assertEquals(InvitationStatus.EXPIRED, stale.getStatus());
        assertEquals(InvitationStatus.PENDING, created.status());
        assertEquals("person@example.com", created.email());
        assertEquals(72, java.time.Duration
                .between(created.lastSentAt(), created.expiresAt()).toHours());
    }

    @Test
    void resendReplacesTokenAndRestartsExpiry() {
        Invitation invitation = invitation(InvitationStatus.EXPIRED);
        invitation.setTokenHash("old-hash");
        invitation.setLastSentAt(LocalDateTime.now().minusHours(73));
        invitation.setExpiresAt(LocalDateTime.now().minusHours(1));
        when(invitations.findByIdAndTenant_Id(invitation.getId(), workspaceId))
                .thenReturn(Optional.of(invitation));

        service.resend(workspaceId, admin.getId(), invitation.getId());

        assertEquals(InvitationStatus.PENDING, invitation.getStatus());
        assertNotEquals("old-hash", invitation.getTokenHash());
        assertEquals(72, java.time.Duration
                .between(invitation.getLastSentAt(), invitation.getExpiresAt()).toHours());
    }

    @Test
    void resendEnforcesCooldown() {
        Invitation invitation = invitation(InvitationStatus.PENDING);
        invitation.setLastSentAt(LocalDateTime.now().minusSeconds(10));
        invitation.setExpiresAt(LocalDateTime.now().plusHours(72));
        when(invitations.findByIdAndTenant_Id(invitation.getId(), workspaceId))
                .thenReturn(Optional.of(invitation));

        assertThrows(BadRequestException.class,
                () -> service.resend(workspaceId, admin.getId(), invitation.getId()));
    }

    @Test
    void nonAdminCannotInvite() {
        givenMembership(member, WorkspaceRole.MEMBER);

        assertThrows(UnauthorizedException.class, () -> service.invite(
                workspaceId, member.getId(), "new@example.com", WorkspaceRole.MEMBER));
    }

    @Test
    void finalActiveAdminCannotBeDemoted() {
        when(members.findByUser_IdAndWorkspace_IdForUpdate(member.getId(), workspaceId))
                .thenReturn(Optional.of(membershipOf(member, WorkspaceRole.WORKSPACE_ADMIN)));
        when(members.countByWorkspace_IdAndRole(workspaceId, WorkspaceRole.WORKSPACE_ADMIN))
                .thenReturn(1L);

        assertThrows(BadRequestException.class, () -> service.updateRole(
                workspaceId, admin.getId(), member.getId(), WorkspaceRole.MEMBER));
        verify(members, never()).save(any(WorkspaceMember.class));
    }

    @Test
    void demotingOneOfTwoAdminsIsAllowed() {
        WorkspaceMember target = membershipOf(member, WorkspaceRole.WORKSPACE_ADMIN);
        when(members.findByUser_IdAndWorkspace_IdForUpdate(member.getId(), workspaceId))
                .thenReturn(Optional.of(target));
        when(members.countByWorkspace_IdAndRole(workspaceId, WorkspaceRole.WORKSPACE_ADMIN))
                .thenReturn(2L);

        service.updateRole(workspaceId, admin.getId(), member.getId(), WorkspaceRole.MEMBER);

        ArgumentCaptor<WorkspaceMember> saved = ArgumentCaptor.forClass(WorkspaceMember.class);
        verify(members).save(saved.capture());
        assertEquals(WorkspaceRole.MEMBER, saved.getValue().getRole());
    }

    @Test
    void nobodyCanChangeTheirOwnRole() {
        assertThrows(BadRequestException.class, () -> service.updateRole(
                workspaceId, admin.getId(), admin.getId(), WorkspaceRole.MEMBER));
    }

    @Test
    void theOrganizationOwnerCannotBeDeactivatedFromAWorkspace() {
        assertThrows(BadRequestException.class, () -> service.updateStatus(
                workspaceId, admin.getId(), admin.getId(), UserStatus.INACTIVE));
    }

    @Test
    void deactivationPublishesTheSessionRevocationEvent() {
        service.updateStatus(workspaceId, admin.getId(), member.getId(), UserStatus.INACTIVE);

        assertEquals(UserStatus.INACTIVE, member.getStatus());
        ArgumentCaptor<Object> captured = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captured.capture());
        assertEquals(new com.cacanode.api.tenant.api.event.UserDeactivatedEvent(
                workspaceId, member.getId()), captured.getValue());
    }

    @Test
    void selfRegistrationIsRefusedWhenTheToggleIsOff() {
        when(users.count()).thenReturn(1L);

        assertThrows(ConflictException.class,
                () -> service.register("new@example.com", "New Person", "encoded"));
    }

    @Test
    void selfRegistrationJoinsTheDefaultAndEveryPublicWorkspace() {
        when(users.count()).thenReturn(1L);
        Tenant finance = workspace("Finance", "finance", WorkspaceVisibility.PUBLIC, false);
        Tenant privateOne = workspace("Executives", "executives", WorkspaceVisibility.PRIVATE, false);
        organization.setAllowSelfRegistration(true);
        when(workspaces.findAll()).thenReturn(List.of(workspace, finance, privateOne));
        when(users.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);

        var accepted = service.register("new@example.com", "New Person", "encoded");

        assertEquals(UserStatus.ACTIVE.name(), accepted.status());
        assertEquals(workspaceId, accepted.workspaceId(), "lands in the default workspace");
        assertEquals(OrgRole.MEMBER, accepted.orgRole());
        // Two memberships saved: default + public Finance; the private one is skipped.
        verify(members, org.mockito.Mockito.times(2)).save(any(WorkspaceMember.class));
    }

    @Test
    void selfRegistrationCannotTakeAnExistingEmail() {
        when(users.count()).thenReturn(1L);
        organization.setAllowSelfRegistration(true);
        when(workspaces.findAll()).thenReturn(List.of(workspace));
        when(users.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.register("Taken@Example.com", "Someone", "encoded"));
    }

    private void givenMembership(User user, WorkspaceRole role) {
        WorkspaceMember membership = membershipOf(user, role);
        when(members.findByUser_IdAndWorkspace_Id(user.getId(), workspaceId))
                .thenReturn(Optional.of(membership));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
    }

    private WorkspaceMember membershipOf(User user, WorkspaceRole role) {
        WorkspaceMember membership = new WorkspaceMember();
        membership.setId(UUID.randomUUID());
        membership.setUser(user);
        membership.setWorkspace(workspace);
        membership.setRole(role);
        membership.setCreatedAt(LocalDateTime.now());
        return membership;
    }

    private Tenant workspace(String name, String slug, WorkspaceVisibility visibility, boolean isDefault) {
        Tenant value = new Tenant();
        value.setId(UUID.randomUUID());
        value.setOrganization(organization);
        value.setName(name);
        value.setSlug(slug);
        value.setStatus(TenantStatus.ACTIVE);
        value.setVisibility(visibility);
        value.setDefaultWorkspace(isDefault);
        value.setCreatedAt(LocalDateTime.now());
        return value;
    }

    private User user(String email, OrgRole role) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setOrganization(organization);
        user.setEmail(email);
        user.setFullName("Test User");
        user.setPasswordHash("hash");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    private Invitation invitation(InvitationStatus status) {
        Invitation invitation = new Invitation();
        invitation.setId(UUID.randomUUID());
        invitation.setCreatedAt(LocalDateTime.now());
        invitation.setTenant(workspace);
        invitation.setInvitedBy(admin);
        invitation.setEmail("invite@example.com");
        invitation.setRole(WorkspaceRole.MEMBER);
        invitation.setStatus(status);
        invitation.setTokenHash("hash");
        invitation.setLastSentAt(LocalDateTime.now());
        invitation.setExpiresAt(LocalDateTime.now().plusHours(72));
        return invitation;
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }
}
