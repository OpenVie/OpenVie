package com.cacanode.api.tenant.service.implement;

import com.cacanode.api.common.exception.custom.UnauthorizedException;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.UserStatus;
import com.cacanode.api.tenant.api.WorkspaceRole;
import com.cacanode.api.tenant.model.Tenant;
import com.cacanode.api.tenant.model.User;
import com.cacanode.api.tenant.model.WorkspaceMember;
import com.cacanode.api.tenant.repository.TenantRepository;
import com.cacanode.api.tenant.repository.UserRepository;
import com.cacanode.api.tenant.repository.WorkspaceMemberRepository;
import com.cacanode.api.tenant.service.TenantUserManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Identity and membership contract implementation.
 *
 * <p>Account creation (self-registration and invitation acceptance) is
 * delegated to {@link TenantUserManagementService}, which owns the users and
 * membership writes. That service reads membership straight from the
 * repositories, so the two collaborators stay acyclic.
 */
@Service
@Slf4j(topic = "TENANT-API")
@RequiredArgsConstructor
public class TenantModuleApiImpl implements TenantIdentityApi {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final TenantUserManagementService userManagementService;

    @Override
    @Transactional(readOnly = true)
    public AuthenticatedIdentity authenticate(String email, String password) {
        String normalized = email == null ? "" : email.trim();
        var found = userRepository.findByEmailIgnoreCase(normalized);
        if (found.isEmpty()) {
            // Spend a hash so a missing account and a wrong password cost the
            // same, which keeps login from enumerating accounts.
            passwordEncoder.matches(password, DUMMY_HASH);
            return null;
        }
        User user = found.get();
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            return null;
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException(statusMessage(user.getStatus()));
        }

        List<WorkspaceMember> memberships = memberRepository.findVisibleByUserId(user.getId());
        if (memberships.isEmpty()) {
            // Without a workspace there is nothing to scope a token to.
            throw new UnauthorizedException("This account has no workspace access");
        }
        WorkspaceMember landing = memberships.stream()
                .filter(member -> member.getWorkspace().isDefaultWorkspace())
                .findFirst()
                .orElse(memberships.get(0));

        return new AuthenticatedIdentity(identity(user), landing.getWorkspace().getId(), landing.getRole());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean accountExists() {
        return userRepository.count() > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public RegistrationStatus registrationStatus() {
        if (userRepository.count() == 0) {
            return new RegistrationStatus(true, false);
        }
        boolean allowed = tenantRepository.findAll().stream()
                .anyMatch(workspace -> workspace.getOrganization().isAllowSelfRegistration());
        return new RegistrationStatus(false, allowed);
    }

    @Override
    @Transactional(readOnly = true)
    public IdentitySnapshot findUserById(UUID userId) {
        return userRepository.findById(userId).map(this::identity).orElse(null);
    }


    @Override
    @Transactional(readOnly = true)
    public MembershipSnapshot requireMembership(UUID userId, UUID workspaceId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Session is no longer valid"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException(statusMessage(user.getStatus()));
        }
        WorkspaceMember membership = memberRepository
                .findByUser_IdAndWorkspace_Id(userId, workspaceId)
                .orElseThrow(() -> new UnauthorizedException("You are not a member of this workspace"));
        Tenant workspace = membership.getWorkspace();
        if (!workspace.getOrganization().getId().equals(user.getOrganization().getId())) {
            throw new UnauthorizedException("Workspace does not belong to your organization");
        }
        if (workspace.getStatus() == com.cacanode.api.tenant.api.TenantStatus.ARCHIVED) {
            throw new UnauthorizedException("This workspace is archived");
        }
        return new MembershipSnapshot(
                userId,
                user.getOrganization().getId(),
                workspaceId,
                user.getRole(),
                membership.getRole(),
                user.getStatus().name());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkspaceSummary> listMemberships(UUID userId) {
        return memberRepository.findVisibleByUserId(userId).stream()
                .map(member -> new WorkspaceSummary(
                        member.getWorkspace().getId(),
                        member.getWorkspace().getName(),
                        member.getWorkspace().getSlug(),
                        member.getRole(),
                        member.getWorkspace().isDefaultWorkspace()))
                .toList();
    }

    @Override
    @Transactional
    public AcceptedAccount register(String email, String fullName, String passwordHash) {
        return userManagementService.register(email, fullName, passwordHash);
    }

    @Override
    @Transactional(readOnly = true)
    public InvitationSnapshot validateInvitation(String rawToken) {
        return userManagementService.validateInvitationToken(rawToken);
    }

    @Override
    @Transactional
    public AcceptedAccount acceptInvitation(String rawToken, String fullName, String passwordHash) {
        return userManagementService.acceptInvitationToken(rawToken, fullName, passwordHash);
    }

    private IdentitySnapshot identity(User user) {
        return new IdentitySnapshot(
                user.getId(),
                user.getOrganization().getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getStatus().name(),
                user.isMustChangePassword());
    }

    private String statusMessage(UserStatus status) {
        return switch (status) {
            case SUSPENDED -> "Account suspended.";
            case PENDING, INVITED -> "This account has not been activated yet.";
            default -> "User account is disabled";
        };
    }

    /** Valid bcrypt digest of a random value; never matches a real password. */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
}
