package com.cacanode.api.tenant.service;

import com.cacanode.api.common.cache.BusinessCache;
import com.cacanode.api.common.cache.BusinessCacheInvalidationPublisher;
import com.cacanode.api.common.cache.CacheKeyFactory;
import com.cacanode.api.common.cache.VersionedJsonCache;
import com.cacanode.api.common.enums.LogAction;
import com.cacanode.api.common.event.AuditLogEvent;
import com.cacanode.api.common.event.durable.DurableEventPublisher;
import com.cacanode.api.common.exception.custom.BadRequestException;
import com.cacanode.api.common.exception.custom.ConflictException;
import com.cacanode.api.common.exception.custom.ResourceNotFoundException;
import com.cacanode.api.common.exception.custom.UnauthorizedException;
import com.cacanode.api.tenant.api.DeliveryAvailability;
import com.cacanode.api.tenant.api.TenantIdentityApi.AcceptedAccount;
import com.cacanode.api.tenant.api.TenantIdentityApi.InvitationSnapshot;
import com.cacanode.api.tenant.api.TenantIdentityApi.MembershipSnapshot;
import com.cacanode.api.tenant.api.event.UserDeactivatedEvent;
import com.cacanode.api.tenant.api.event.UserInvitedEvent;
import com.cacanode.api.tenant.dto.UserManagementDtos.DirectoryResponse;
import com.cacanode.api.tenant.dto.UserManagementDtos.InvitationResponse;
import com.cacanode.api.tenant.dto.UserManagementDtos.MemberResponse;
import com.cacanode.api.tenant.api.InvitationStatus;
import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.UserStatus;
import com.cacanode.api.tenant.api.WorkspaceRole;
import com.cacanode.api.tenant.enums.WorkspaceVisibility;
import com.cacanode.api.tenant.model.Invitation;
import com.cacanode.api.tenant.model.Tenant;
import com.cacanode.api.tenant.model.User;
import com.cacanode.api.tenant.model.WorkspaceMember;
import com.cacanode.api.tenant.repository.InvitationRepository;
import com.cacanode.api.tenant.repository.TenantRepository;
import com.cacanode.api.tenant.repository.UserRepository;
import com.cacanode.api.tenant.repository.WorkspaceMemberRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Member and invitation management scoped to one workspace.
 *
 * <p>An invitation is only created when the organization has an enabled
 * notification channel: an undeliverable token is worse than a refused
 * request, because the invitee never learns it exists.
 */
@Service
public class TenantUserManagementService {
    private static final Duration INVITATION_LIFETIME = Duration.ofHours(72);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final Duration INVITATION_VALIDITY_GRACE = Duration.ZERO;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final InvitationRepository invitationRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final TenantRepository workspaceRepository;
    private final DeliveryAvailability channels;
    private final ApplicationEventPublisher eventPublisher;
    @Autowired(required = false)
    private DurableEventPublisher durableEventPublisher;
    @Autowired(required = false)
    private VersionedJsonCache businessCache;
    @Autowired(required = false)
    private CacheKeyFactory cacheKeyFactory;
    @Autowired(required = false)
    private BusinessCacheInvalidationPublisher businessInvalidationPublisher;

    @Autowired
    public TenantUserManagementService(
            UserRepository userRepository,
            InvitationRepository invitationRepository,
            WorkspaceMemberRepository memberRepository,
            TenantRepository workspaceRepository,
            DeliveryAvailability channels,
            ApplicationEventPublisher eventPublisher
    ) {
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
        this.memberRepository = memberRepository;
        this.workspaceRepository = workspaceRepository;
        this.channels = channels;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public DirectoryResponse getDirectory(UUID workspaceId, UUID currentUserId) {
        MembershipSnapshot actor = requireAdmin(workspaceId, currentUserId);
        DirectoryResponse snapshot = businessCache == null || cacheKeyFactory == null
                ? loadDirectorySnapshot(actor.orgId(), workspaceId)
                : businessCache.getOrLoad(
                BusinessCache.USER_DIRECTORY,
                cacheKeyFactory.build("user-directory", "workspace", workspaceId.toString()),
                DirectoryResponse.class,
                () -> loadDirectorySnapshot(actor.orgId(), workspaceId));
        return decorateDirectory(snapshot, currentUserId, LocalDateTime.now());
    }

    private DirectoryResponse loadDirectorySnapshot(UUID orgId, UUID workspaceId) {
        LocalDateTime now = LocalDateTime.now();
        List<Invitation> invitations = invitationRepository.findByTenant_IdOrderByCreatedAtDesc(workspaceId);
        invitations.stream()
                .filter(invitation -> invitation.getStatus() == InvitationStatus.PENDING
                        && !invitation.getExpiresAt().isAfter(now))
                .forEach(invitation -> invitation.setStatus(InvitationStatus.EXPIRED));

        List<MemberResponse> members = memberRepository
                .findByWorkspace_IdOrderByRoleAscCreatedAtAsc(workspaceId).stream()
                .map(membership -> toMember(membership, null))
                .toList();
        List<InvitationResponse> pending = invitations.stream()
                .filter(invitation -> invitation.getStatus() == InvitationStatus.PENDING
                        || invitation.getStatus() == InvitationStatus.EXPIRED)
                .map(this::toInvitation)
                .toList();
        return new DirectoryResponse(members, pending);
    }

    /**
     * Creates an invitation for a workspace. Requires an enabled channel; when
     * none exists the caller is told to configure one rather than being handed
     * a token nobody will receive.
     */
    @Transactional
    public InvitationResponse invite(UUID workspaceId, UUID actorId, String rawEmail, WorkspaceRole role) {
        MembershipSnapshot actor = requireAdmin(workspaceId, actorId);
        requireChannel(actor.orgId());

        String email = normalizeEmail(rawEmail);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account already exists for this email");
        }

        LocalDateTime now = LocalDateTime.now();
        invitationRepository.findFirstByTenant_IdAndEmailIgnoreCaseAndStatus(
                workspaceId, email, InvitationStatus.PENDING).ifPresent(existing -> {
            if (existing.getExpiresAt().isAfter(now)) {
                throw new ConflictException("A pending invitation already exists for this email");
            }
            existing.setStatus(InvitationStatus.EXPIRED);
            invitationRepository.saveAndFlush(existing);
        });

        Tenant workspace = requireWorkspace(workspaceId);
        User actorUser = requireUser(actorId);
        String token = generateToken();
        Invitation invitation = new Invitation();
        invitation.setTenant(workspace);
        invitation.setInvitedBy(actorUser);
        invitation.setEmail(email);
        invitation.setRole(role);
        invitation.setTokenHash(hashToken(token));
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setLastSentAt(now);
        invitation.setExpiresAt(now.plus(INVITATION_LIFETIME));
        invitationRepository.save(invitation);

        publishInvitationEmail(invitation, token);
        audit(workspaceId, actorId, LogAction.USER_INVITE, "invitation", invitation.getId(),
                Map.of("role", role.name(), "transition", "NONE->PENDING"));
        invalidateMembers(workspaceId);
        return toInvitation(invitation);
    }

    @Transactional
    public InvitationResponse resend(UUID workspaceId, UUID actorId, UUID invitationId) {
        MembershipSnapshot actor = requireAdmin(workspaceId, actorId);
        requireChannel(actor.orgId());
        Invitation invitation = requireInvitation(workspaceId, invitationId);
        if (invitation.getStatus() != InvitationStatus.PENDING
                && invitation.getStatus() != InvitationStatus.EXPIRED) {
            throw new BadRequestException("Only pending or expired invitations can be resent");
        }
        LocalDateTime now = LocalDateTime.now();
        invitationRepository.findFirstByTenant_IdAndEmailIgnoreCaseAndStatus(
                        workspaceId, invitation.getEmail(), InvitationStatus.PENDING)
                .filter(existing -> !existing.getId().equals(invitationId))
                .ifPresent(existing -> {
                    throw new ConflictException("Another pending invitation already exists for this email");
                });
        long waitSeconds = RESEND_COOLDOWN.minus(Duration.between(invitation.getLastSentAt(), now)).toSeconds();
        if (waitSeconds > 0) {
            throw new BadRequestException("Please wait " + waitSeconds + " seconds before resending");
        }

        String token = generateToken();
        invitation.setTokenHash(hashToken(token));
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setLastSentAt(now);
        invitation.setExpiresAt(now.plus(INVITATION_LIFETIME));
        invitationRepository.save(invitation);

        publishInvitationEmail(invitation, token);
        audit(workspaceId, actorId, LogAction.USER_INVITATION_RESENT, "invitation", invitationId,
                Map.of("transition", "PENDING_OR_EXPIRED->PENDING"));
        invalidateMembers(workspaceId);
        return toInvitation(invitation);
    }

    @Transactional
    public void cancel(UUID workspaceId, UUID actorId, UUID invitationId) {
        requireAdmin(workspaceId, actorId);
        Invitation invitation = requireInvitation(workspaceId, invitationId);
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BadRequestException("Only pending invitations can be cancelled");
        }
        invitation.setStatus(InvitationStatus.CANCELLED);
        invitationRepository.save(invitation);
        audit(workspaceId, actorId, LogAction.USER_INVITATION_CANCELLED, "invitation", invitationId,
                Map.of("transition", "PENDING->CANCELLED"));
        invalidateMembers(workspaceId);
    }

    /** Changes a member's role inside one workspace. */
    @Transactional
    public MemberResponse updateRole(UUID workspaceId, UUID actorId, UUID userId, WorkspaceRole role) {
        requireAdmin(workspaceId, actorId);
        if (actorId.equals(userId)) {
            throw new BadRequestException("You cannot change your own role");
        }
        WorkspaceMember membership = memberRepository
                .findByUser_IdAndWorkspace_IdForUpdate(userId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        WorkspaceRole previous = membership.getRole();
        if (previous == WorkspaceRole.WORKSPACE_ADMIN && role != WorkspaceRole.WORKSPACE_ADMIN) {
            ensureNotFinalAdmin(workspaceId);
        }
        membership.setRole(role);
        memberRepository.save(membership);
        audit(workspaceId, actorId, LogAction.USER_ROLE_CHANGED, "user", userId,
                Map.of("from", previous.name(), "to", role.name()));
        invalidateMembers(workspaceId);
        return toMember(membership, actorId);
    }

    /** Activates or deactivates a member's account within this workspace's org. */
    @Transactional
    public MemberResponse updateStatus(UUID workspaceId, UUID actorId, UUID userId, UserStatus status) {
        MembershipSnapshot actor = requireAdmin(workspaceId, actorId);
        if (status != UserStatus.ACTIVE && status != UserStatus.INACTIVE) {
            throw new BadRequestException("Status must be ACTIVE or INACTIVE");
        }
        if (actorId.equals(userId) && status == UserStatus.INACTIVE) {
            throw new BadRequestException("You cannot deactivate your own account");
        }
        User user = requireMembershipUser(workspaceId, userId);
        if (user.getRole() == OrgRole.ORG_OWNER) {
            throw new BadRequestException("The organization owner cannot be deactivated here");
        }
        if (status == UserStatus.INACTIVE) {
            ensureNotFinalAdminIfApplicable(workspaceId, user);
        }
        user.setStatus(status);
        userRepository.save(user);
        if (status == UserStatus.INACTIVE) {
            publishBusinessEvent("tenant.user.deactivated.v1", new UserDeactivatedEvent(workspaceId, userId));
        }
        audit(workspaceId, actorId,
                status == UserStatus.ACTIVE ? LogAction.USER_REACTIVATED : LogAction.USER_DEACTIVATED,
                "user", userId, Map.of("status", status.name()));
        invalidateMembers(workspaceId);
        return toMember(memberRepository.findByUser_IdAndWorkspace_Id(userId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found")), actorId);
    }

    @Transactional(readOnly = true)
    public InvitationSnapshot validateInvitationToken(String rawToken) {
        Invitation invitation = findInvitation(rawToken, false);
        validateAcceptable(invitation);
        return new InvitationSnapshot(
                invitation.getEmail(),
                invitation.getTenant().getOrganization().getName(),
                invitation.getTenant().getName(),
                invitation.getRole(),
                invitation.getExpiresAt());
    }

    @Transactional
    public AcceptedAccount acceptInvitationToken(String rawToken, String fullName, String passwordHash) {
        Invitation invitation = findInvitation(rawToken, true);
        return acceptLockedInvitation(invitation, fullName, passwordHash);
    }

    private AcceptedAccount acceptLockedInvitation(
            Invitation invitation, String fullName, String passwordHash) {
        validateAcceptable(invitation);
        String email = normalizeEmail(invitation.getEmail());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account already exists for this email");
        }
        Tenant workspace = invitation.getTenant();

        User user = new User();
        user.setOrganization(workspace.getOrganization());
        user.setInvitedBy(invitation.getInvitedBy());
        user.setEmail(email);
        user.setFullName(fullName.trim());
        user.setPasswordHash(passwordHash);
        user.setRole(OrgRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);
        user.setMustChangePassword(false);
        userRepository.save(user);

        grantMembership(user, workspace, invitation.getRole());

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(LocalDateTime.now());
        invitationRepository.save(invitation);

        audit(workspace.getId(), user.getId(), LogAction.USER_INVITATION_ACCEPTED,
                "invitation", invitation.getId(), Map.of("transition", "PENDING->ACCEPTED"));
        invalidateMembers(workspace.getId());
        return new AcceptedAccount(
                user.getId(), workspace.getOrganization().getId(), workspace.getId(),
                user.getEmail(), user.getFullName(), user.getRole(), invitation.getRole(),
                user.getStatus().name(), user.isMustChangePassword());
    }

    /**
     * Self-registration: joins the default workspace plus every PUBLIC
     * workspace. Refused when the organization disabled self-registration.
     */
    @Transactional
    public AcceptedAccount register(String email, String fullName, String passwordHash) {
        String normalized = normalizeEmail(email);
        if (userRepository.existsByEmailIgnoreCase(normalized)) {
            throw new ConflictException("An account already exists for this email");
        }
        List<Tenant> joinable = joinableWorkspaces();
        if (joinable.isEmpty()) {
            throw new ConflictException("Self-registration is not enabled for this installation");
        }
        Tenant anchor = joinable.stream()
                .filter(Tenant::isDefaultWorkspace)
                .findFirst()
                .orElse(joinable.get(0));

        User user = new User();
        user.setOrganization(anchor.getOrganization());
        user.setEmail(normalized);
        user.setFullName(fullName == null || fullName.isBlank() ? normalized : fullName.trim());
        user.setPasswordHash(passwordHash);
        user.setRole(OrgRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        for (Tenant workspace : joinable) {
            grantMembership(user, workspace, WorkspaceRole.MEMBER);
        }
        audit(anchor.getId(), user.getId(), LogAction.MEMBER_ADDED, "user", user.getId(),
                Map.of("via", "self-registration", "workspaces", joinable.size()));
        joinable.forEach(workspace -> invalidateMembers(workspace.getId()));

        return new AcceptedAccount(
                user.getId(), user.getOrganization().getId(), anchor.getId(),
                user.getEmail(), user.getFullName(), user.getRole(), WorkspaceRole.MEMBER,
                user.getStatus().name(), user.isMustChangePassword());
    }

    /** Workspaces a self-registering account may join, honoring the org toggle. */
    private List<Tenant> joinableWorkspaces() {
        List<Tenant> all = workspaceRepository.findAll().stream()
                .filter(workspace -> workspace.getStatus()
                        == com.cacanode.api.tenant.api.TenantStatus.ACTIVE)
                .toList();
        if (all.isEmpty()) {
            return List.of();
        }
        boolean allowed = all.stream()
                .anyMatch(workspace -> workspace.getOrganization().isAllowSelfRegistration());
        if (!allowed) {
            return List.of();
        }
        return all.stream()
                .filter(workspace -> workspace.getOrganization().isAllowSelfRegistration())
                .filter(workspace -> workspace.isDefaultWorkspace()
                        || workspace.getVisibility() == WorkspaceVisibility.PUBLIC)
                .toList();
    }

    private void grantMembership(User user, Tenant workspace, WorkspaceRole role) {
        if (memberRepository.findByUser_IdAndWorkspace_Id(user.getId(), workspace.getId()).isPresent()) {
            return;
        }
        WorkspaceMember membership = new WorkspaceMember();
        membership.setUser(user);
        membership.setWorkspace(workspace);
        membership.setRole(role);
        memberRepository.save(membership);
    }

    private DirectoryResponse decorateDirectory(
            DirectoryResponse snapshot, UUID currentUserId, LocalDateTime now) {
        List<MemberResponse> members = snapshot.members().stream()
                .map(member -> new MemberResponse(member.id(), member.email(), member.fullName(),
                        member.workspaceRole(), member.status(), member.joinedAt(), member.lastLoginAt(),
                        member.id().equals(currentUserId)))
                .toList();
        List<InvitationResponse> invitations = snapshot.invitations().stream()
                .map(invitation -> invitation.status() == InvitationStatus.PENDING
                                && !invitation.expiresAt().isAfter(now)
                        ? new InvitationResponse(invitation.id(), invitation.email(), invitation.role(),
                        InvitationStatus.EXPIRED, invitation.invitedAt(), invitation.expiresAt(),
                        invitation.lastSentAt())
                        : invitation)
                .filter(invitation -> invitation.status() == InvitationStatus.PENDING
                        || invitation.status() == InvitationStatus.EXPIRED)
                .toList();
        return new DirectoryResponse(members, invitations);
    }

    private void requireChannel(UUID orgId) {
        if (!channels.enabledFor(orgId)) {
            throw new ConflictException(
                    "No notification channel is configured, so invitations cannot be delivered. "
                            + "Enable one in organization settings.");
        }
    }

    /**
     * Resolves the actor's authority in this workspace straight from the
     * repositories. Membership is read here rather than through the identity
     * API so that the identity implementation can delegate account creation
     * back to this service without a bean cycle.
     */
    private MembershipSnapshot requireAdmin(UUID workspaceId, UUID actorId) {
        MembershipSnapshot membership = resolveMembership(actorId, workspaceId);
        if (!membership.isWorkspaceAdmin()) {
            throw new UnauthorizedException("This action requires workspace admin authority");
        }
        return membership;
    }

    private MembershipSnapshot resolveMembership(UUID userId, UUID workspaceId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Session is no longer valid"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException("User account is disabled");
        }
        WorkspaceMember membership = memberRepository
                .findByUser_IdAndWorkspace_Id(userId, workspaceId)
                .orElseThrow(() -> new UnauthorizedException("You are not a member of this workspace"));
        Tenant workspace = membership.getWorkspace();
        if (!workspace.getOrganization().getId().equals(user.getOrganization().getId())) {
            throw new UnauthorizedException("Workspace does not belong to your organization");
        }
        return new MembershipSnapshot(
                userId, user.getOrganization().getId(), workspaceId,
                user.getRole(), membership.getRole(), user.getStatus().name());
    }

    private Tenant requireWorkspace(UUID workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace was not found"));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private User requireMembershipUser(UUID workspaceId, UUID userId) {
        return memberRepository.findByUser_IdAndWorkspace_Id(userId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"))
                .getUser();
    }

    private void ensureNotFinalAdmin(UUID workspaceId) {
        if (memberRepository.countByWorkspace_IdAndRole(workspaceId, WorkspaceRole.WORKSPACE_ADMIN) <= 1) {
            throw new BadRequestException("A workspace needs at least one active admin");
        }
    }

    private void ensureNotFinalAdminIfApplicable(UUID workspaceId, User user) {
        WorkspaceMember membership = memberRepository
                .findByUser_IdAndWorkspace_Id(user.getId(), workspaceId).orElse(null);
        if (membership != null && membership.getRole() == WorkspaceRole.WORKSPACE_ADMIN) {
            ensureNotFinalAdmin(workspaceId);
        }
    }

    private Invitation requireInvitation(UUID workspaceId, UUID invitationId) {
        return invitationRepository.findByIdAndTenant_Id(invitationId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));
    }

    private void validateAcceptable(Invitation invitation) {
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ResourceNotFoundException("Invitation is invalid or no longer available");
        }
        if (!invitation.getExpiresAt().isAfter(LocalDateTime.now().minus(INVITATION_VALIDITY_GRACE))) {
            throw new BadRequestException("Invitation has expired");
        }
    }

    private void invalidateMembers(UUID workspaceId) {
        if (businessInvalidationPublisher != null) {
            businessInvalidationPublisher.memberMutation(workspaceId);
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void publishInvitationEmail(Invitation invitation, String token) {
        publishBusinessEvent("tenant.user.invited.v1", new UserInvitedEvent(
                invitation.getTenant().getId(),
                invitation.getInvitedBy().getId(),
                invitation.getEmail(),
                invitation.getTenant().getOrganization().getName(),
                invitation.getTenant().getName(),
                invitation.getRole().name(),
                token,
                invitation.getExpiresAt(),
                invitation.getId(),
                invitation.getStatus().name(),
                invitation.getCreatedAt() == null ? LocalDateTime.now() : invitation.getCreatedAt()));
    }

    private void publishBusinessEvent(String stableType, Object event) {
        if (durableEventPublisher != null) {
            durableEventPublisher.publish(stableType, 1, event);
        } else {
            eventPublisher.publishEvent(event);
        }
    }

    private void audit(UUID workspaceId, UUID actorId, LogAction action, String resourceType,
                       UUID resourceId, Map<String, Object> metadata) {
        eventPublisher.publishEvent(AuditLogEvent.builder(this)
                .tenantId(workspaceId).userId(actorId).action(action).resourceType(resourceType)
                .resourceId(resourceId).metadata(metadata).build());
    }

    private MemberResponse toMember(WorkspaceMember membership, UUID currentUserId) {
        User user = membership.getUser();
        return new MemberResponse(user.getId(), user.getEmail(), user.getFullName(),
                membership.getRole(), user.getStatus(), membership.getCreatedAt(),
                user.getLastLoginAt(),
                currentUserId != null && user.getId().equals(currentUserId));
    }

    private MemberResponse toMember(User user, WorkspaceMember membership, UUID currentUserId) {
        return new MemberResponse(user.getId(), user.getEmail(), user.getFullName(),
                membership.getRole(), user.getStatus(), membership.getCreatedAt(),
                user.getLastLoginAt(),
                currentUserId != null && user.getId().equals(currentUserId));
    }

    private InvitationResponse toInvitation(Invitation invitation) {
        return new InvitationResponse(invitation.getId(), invitation.getEmail(), invitation.getRole(),
                invitation.getStatus(), invitation.getCreatedAt(), invitation.getExpiresAt(),
                invitation.getLastSentAt());
    }

    String hashToken(String token) {
        return base64Sha256(token);
    }

    private String legacyHashToken(String token) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String base64Sha256(String token) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Invitation findInvitation(String rawToken, boolean lock) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ResourceNotFoundException("Invitation is invalid or no longer available");
        }
        var canonical = lock
                ? invitationRepository.findByTokenHashForUpdate(hashToken(rawToken))
                : invitationRepository.findByTokenHash(hashToken(rawToken));
        return canonical.orElseGet(() -> (lock
                ? invitationRepository.findByTokenHashForUpdate(legacyHashToken(rawToken))
                : invitationRepository.findByTokenHash(legacyHashToken(rawToken)))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Invitation is invalid or no longer available")));
    }
}
