package com.cacanode.api.tenant.service;

import com.cacanode.api.common.exception.custom.BadRequestException;
import com.cacanode.api.common.exception.custom.ConflictException;
import com.cacanode.api.tenant.enums.InvitationStatus;
import com.cacanode.api.tenant.api.TenantStatus;
import com.cacanode.api.tenant.enums.UserRole;
import com.cacanode.api.tenant.enums.UserStatus;
import com.cacanode.api.tenant.model.Invitation;
import com.cacanode.api.tenant.model.Tenant;
import com.cacanode.api.tenant.model.User;
import com.cacanode.api.tenant.repository.InvitationRepository;
import com.cacanode.api.tenant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantUserManagementServiceTest {
    private UserRepository users;
    private InvitationRepository invitations;
    private ApplicationEventPublisher events;
    private TenantUserManagementService service;
    private Tenant tenant;
    private User admin;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        invitations = mock(InvitationRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new TenantUserManagementService(users, invitations, events);

        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Acme");
        tenant.setStatus(TenantStatus.ACTIVE);
        admin = user(UserRole.TENANT_ADMIN, UserStatus.ACTIVE);
        when(users.findByIdAndTenant_Id(admin.getId(), tenant.getId())).thenReturn(Optional.of(admin));
        when(invitations.save(any())).thenAnswer(call -> {
            Invitation invitation = call.getArgument(0);
            if (invitation.getId() == null) invitation.setId(UUID.randomUUID());
            if (invitation.getCreatedAt() == null) invitation.setCreatedAt(LocalDateTime.now());
            return invitation;
        });
        when(users.save(any())).thenAnswer(call -> {
            User user = call.getArgument(0);
            if (user.getId() == null) user.setId(UUID.randomUUID());
            if (user.getCreatedAt() == null) user.setCreatedAt(LocalDateTime.now());
            return user;
        });
    }

    @Test
    void invitationRejectsExistingAccount() {
        when(users.existsByEmailIgnoreCase("person@example.com")).thenReturn(true);
        assertThrows(ConflictException.class, () -> service.invite(
                tenant.getId(), admin.getId(), " Person@Example.com ", UserRole.USER));
    }

    @Test
    void resendReplacesTokenAndRestartsExpiry() {
        Invitation invitation = invitation(InvitationStatus.EXPIRED);
        invitation.setTokenHash("old-hash");
        invitation.setLastSentAt(LocalDateTime.now().minusHours(73));
        invitation.setExpiresAt(LocalDateTime.now().minusHours(1));
        when(invitations.findByIdAndTenant_Id(invitation.getId(), tenant.getId())).thenReturn(Optional.of(invitation));

        service.resend(tenant.getId(), admin.getId(), invitation.getId());

        assertEquals(InvitationStatus.PENDING, invitation.getStatus());
        assertNotEquals("old-hash", invitation.getTokenHash());
        assertEquals(72, java.time.Duration.between(invitation.getLastSentAt(), invitation.getExpiresAt()).toHours());
    }

    @Test
    void resendEnforcesCooldown() {
        Invitation invitation = invitation(InvitationStatus.PENDING);
        invitation.setLastSentAt(LocalDateTime.now().minusSeconds(10));
        invitation.setExpiresAt(LocalDateTime.now().plusHours(72));
        when(invitations.findByIdAndTenant_Id(invitation.getId(), tenant.getId())).thenReturn(Optional.of(invitation));
        assertThrows(BadRequestException.class,
                () -> service.resend(tenant.getId(), admin.getId(), invitation.getId()));
    }

    @Test
    void finalActiveAdminCannotBeDemotedOrDeactivated() {
        when(users.countByTenant_IdAndRoleAndStatus(tenant.getId(), UserRole.TENANT_ADMIN, UserStatus.ACTIVE))
                .thenReturn(1L);
        assertThrows(BadRequestException.class,
                () -> service.updateRole(tenant.getId(), UUID.randomUUID(), admin.getId(), UserRole.USER));
        assertThrows(BadRequestException.class,
                () -> service.updateStatus(tenant.getId(), UUID.randomUUID(), admin.getId(), UserStatus.INACTIVE));
    }

    @Test
    void deactivationRevokesSessionsAndAcceptanceCreatesActiveUser() {
        User member = user(UserRole.USER, UserStatus.ACTIVE);
        when(users.findByIdAndTenant_Id(member.getId(), tenant.getId())).thenReturn(Optional.of(member));
        service.updateStatus(tenant.getId(), admin.getId(), member.getId(), UserStatus.INACTIVE);
        verify(events).publishEvent(new com.cacanode.api.tenant.api.event.UserDeactivatedEvent(
                tenant.getId(), member.getId()));

        Invitation invitation = invitation(InvitationStatus.PENDING);
        invitation.setEmail("new@example.com");
        invitation.setExpiresAt(LocalDateTime.now().plusHours(1));
        when(invitations.findByTokenHashForUpdate("hash-secret")).thenReturn(Optional.of(invitation));
        var accepted = service.acceptInvitationHash("hash-secret", "New Person", "encoded");
        assertEquals("new@example.com", accepted.email());
        assertEquals(InvitationStatus.ACCEPTED, invitation.getStatus());
        verify(users).save(org.mockito.ArgumentMatchers.argThat(user ->
                user.getStatus() == UserStatus.ACTIVE && "encoded".equals(user.getPasswordHash())));
    }

    private User user(UserRole role, UserStatus status) {
        User user = new User();
        user.setId(UUID.randomUUID()); user.setCreatedAt(LocalDateTime.now()); user.setTenant(tenant);
        user.setEmail(UUID.randomUUID() + "@example.com"); user.setFullName("Test User");
        user.setPasswordHash("hash"); user.setRole(role); user.setStatus(status);
        return user;
    }

    private Invitation invitation(InvitationStatus status) {
        Invitation invitation = new Invitation();
        invitation.setId(UUID.randomUUID()); invitation.setCreatedAt(LocalDateTime.now());
        invitation.setTenant(tenant); invitation.setInvitedBy(admin); invitation.setEmail("invite@example.com");
        invitation.setRole(UserRole.USER); invitation.setStatus(status); invitation.setTokenHash("hash");
        return invitation;
    }
}
