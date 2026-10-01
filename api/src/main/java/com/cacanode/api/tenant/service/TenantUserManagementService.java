package com.cacanode.api.tenant.service;

import com.cacanode.api.common.enums.LogAction;
import com.cacanode.api.common.cache.BusinessCache;
import com.cacanode.api.common.cache.BusinessCacheInvalidationPublisher;
import com.cacanode.api.common.cache.CacheKeyFactory;
import com.cacanode.api.common.cache.VersionedJsonCache;
import com.cacanode.api.common.event.AuditLogEvent;
import com.cacanode.api.common.service.SynchronousAuditRecorder;
import com.cacanode.api.common.event.durable.DurableEventPublisher;
import com.cacanode.api.tenant.api.event.UserInvitedEvent;
import com.cacanode.api.tenant.api.event.UserDeactivatedEvent;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.common.exception.custom.BadRequestException;
import com.cacanode.api.common.exception.custom.ConflictException;
import com.cacanode.api.common.exception.custom.ResourceNotFoundException;
import com.cacanode.api.tenant.dto.UserManagementDtos.DirectoryResponse;
import com.cacanode.api.tenant.dto.UserManagementDtos.InvitationResponse;
import com.cacanode.api.tenant.dto.UserManagementDtos.MemberResponse;
import com.cacanode.api.tenant.enums.InvitationStatus;
import com.cacanode.api.tenant.enums.UserRole;
import com.cacanode.api.tenant.enums.UserStatus;
import com.cacanode.api.tenant.model.Invitation;
import com.cacanode.api.tenant.model.User;
import com.cacanode.api.tenant.repository.InvitationRepository;
import com.cacanode.api.tenant.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class TenantUserManagementService {
    private static final Duration INVITATION_LIFETIME = Duration.ofHours(72);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final InvitationRepository invitationRepository;
    private final ApplicationEventPublisher eventPublisher;
    @Autowired(required = false)
    private DurableEventPublisher durableEventPublisher;
    @Autowired(required = false)
    private VersionedJsonCache businessCache;
    @Autowired(required = false)
    private CacheKeyFactory cacheKeyFactory;
    @Autowired(required = false)
    private BusinessCacheInvalidationPublisher businessInvalidationPublisher;
    @Autowired(required = false)
    private SynchronousAuditRecorder synchronousAudits;

    @Autowired
    public TenantUserManagementService(
            UserRepository userRepository,
            InvitationRepository invitationRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public DirectoryResponse getDirectory(UUID tenantId, UUID currentUserId) {
        DirectoryResponse snapshot;
        if (businessCache == null || cacheKeyFactory == null) {
            snapshot = loadDirectorySnapshot(tenantId);
        } else {
            snapshot = businessCache.getOrLoad(
                    BusinessCache.USER_DIRECTORY,
                    cacheKeyFactory.build("user-directory", "tenant", tenantId.toString()),
                    DirectoryResponse.class,
                    () -> loadDirectorySnapshot(tenantId)
            );
        }
        return decorateDirectory(snapshot, currentUserId, LocalDateTime.now());
    }

    private DirectoryResponse loadDirectorySnapshot(UUID tenantId) {
        List<Invitation> invitations = invitationRepository.findByTenant_IdOrderByCreatedAtDesc(tenantId);
        LocalDateTime now = LocalDateTime.now();
        invitations.stream()
                .filter(invitation -> invitation.getStatus() == InvitationStatus.PENDING
                        && !invitation.getExpiresAt().isAfter(now))
                .forEach(invitation -> invitation.setStatus(InvitationStatus.EXPIRED));

        List<MemberResponse> members = userRepository.findByTenant_IdOrderByFullNameAsc(tenantId).stream()
                .map(user -> toMember(user, null))
                .toList();
        List<InvitationResponse> pending = invitations.stream()
                .filter(i -> i.getStatus() == InvitationStatus.PENDING || i.getStatus() == InvitationStatus.EXPIRED)
                .map(this::toInvitation)
                .toList();
        return new DirectoryResponse(members, pending);
    }

    @Transactional
    public InvitationResponse invite(UUID tenantId, UUID actorId, String rawEmail, UserRole role) {
        String email = normalizeEmail(rawEmail);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account already exists for this email");
        }

        LocalDateTime now = LocalDateTime.now();
        invitationRepository.findFirstByTenant_IdAndEmailIgnoreCaseAndStatus(
                tenantId, email, InvitationStatus.PENDING).ifPresent(existing -> {
            if (existing.getExpiresAt().isAfter(now)) {
                throw new ConflictException("A pending invitation already exists for this email");
            }
            existing.setStatus(InvitationStatus.EXPIRED);
            invitationRepository.saveAndFlush(existing);
        });

        User actor = requireUser(tenantId, actorId);
        String token = generateToken();
        Invitation invitation = new Invitation();
        invitation.setTenant(actor.getTenant());
        invitation.setInvitedBy(actor);
        invitation.setEmail(email);
        invitation.setRole(role);
        invitation.setTokenHash(hashToken(token));
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setLastSentAt(now);
        invitation.setExpiresAt(now.plus(INVITATION_LIFETIME));
        invitationRepository.save(invitation);

        publishInvitationEmail(invitation, token);
        audit(tenantId, actorId, LogAction.USER_INVITE, "invitation", invitation.getId(),
                Map.of("role", role.name(), "transition", "NONE->PENDING"));
        invalidateMembers(tenantId);
        return toInvitation(invitation);
    }

    @Transactional
    public InvitationResponse resend(UUID tenantId, UUID actorId, UUID invitationId) {
        Invitation invitation = requireInvitation(tenantId, invitationId);
        if (invitation.getStatus() != InvitationStatus.PENDING
                && invitation.getStatus() != InvitationStatus.EXPIRED) {
            throw new BadRequestException("Only pending or expired invitations can be resent");
        }
        LocalDateTime now = LocalDateTime.now();
        invitationRepository.findFirstByTenant_IdAndEmailIgnoreCaseAndStatus(
                tenantId, invitation.getEmail(), InvitationStatus.PENDING)
                .filter(existing -> !existing.getId().equals(invitationId))
                .ifPresent(existing -> { throw new ConflictException(
                        "Another pending invitation already exists for this email"); });
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
        audit(tenantId, actorId, LogAction.USER_INVITATION_RESENT, "invitation", invitationId,
                Map.of("transition", "PENDING_OR_EXPIRED->PENDING"));
        invalidateMembers(tenantId);
        return toInvitation(invitation);
    }

    @Transactional
    public void cancel(UUID tenantId, UUID actorId, UUID invitationId) {
        Invitation invitation = requireInvitation(tenantId, invitationId);
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BadRequestException("Only pending invitations can be cancelled");
        }
        invitation.setStatus(InvitationStatus.CANCELLED);
        invitationRepository.save(invitation);
        audit(tenantId, actorId, LogAction.USER_INVITATION_CANCELLED, "invitation", invitationId,
                Map.of("transition", "PENDING->CANCELLED"));
        invalidateMembers(tenantId);
    }

    @Transactional
    public MemberResponse updateRole(UUID tenantId, UUID actorId, UUID userId, UserRole role) {
        if (actorId.equals(userId)) {
            throw new BadRequestException("You cannot change your own role");
        }
        User user = requireUser(tenantId, userId);
        UserRole previousRole = user.getRole();
        if (previousRole == UserRole.TENANT_ADMIN && role != UserRole.TENANT_ADMIN) {
            ensureNotFinalActiveAdmin(user);
        }
        user.setRole(role);
        userRepository.save(user);
        audit(tenantId, actorId, LogAction.USER_ROLE_CHANGED, "user", userId,
                Map.of("from", previousRole.name(), "to", role.name()));
        invalidateMembers(tenantId);
        return toMember(user, actorId);
    }

    @Transactional
    public MemberResponse updateStatus(UUID tenantId, UUID actorId, UUID userId, UserStatus status) {
        if (status != UserStatus.ACTIVE && status != UserStatus.INACTIVE) {
            throw new BadRequestException("Status must be ACTIVE or INACTIVE");
        }
        if (actorId.equals(userId) && status == UserStatus.INACTIVE) {
            throw new BadRequestException("You cannot deactivate your own account");
        }
        User user = requireUser(tenantId, userId);
        if (status == UserStatus.ACTIVE && user.getStatus() != UserStatus.ACTIVE) {
            }
        if (status == UserStatus.INACTIVE && user.getRole() == UserRole.TENANT_ADMIN) {
            ensureNotFinalActiveAdmin(user);
        }
        user.setStatus(status);
        userRepository.save(user);
        if (status == UserStatus.INACTIVE) {
            publishBusinessEvent("tenant.user.deactivated.v1", new UserDeactivatedEvent(tenantId, userId));
        }
        audit(tenantId, actorId,
                status == UserStatus.ACTIVE ? LogAction.USER_REACTIVATED : LogAction.USER_DEACTIVATED,
                "user", userId, Map.of("status", status.name()));
        invalidateMembers(tenantId);
        return toMember(user, actorId);
    }

    @Transactional(readOnly = true)
    public TenantIdentityApi.InvitationSnapshot validateInvitationToken(String rawToken) {
        Invitation invitation = findInvitation(rawToken, false);
        validateAcceptable(invitation);
        return new TenantIdentityApi.InvitationSnapshot(invitation.getEmail(), invitation.getTenant().getName(),
                invitation.getRole().name(), invitation.getExpiresAt());
    }

    @Deprecated
    @Transactional(readOnly = true)
    public TenantIdentityApi.InvitationSnapshot validateInvitationHash(String tokenHash) {
        Invitation invitation = invitationRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation is invalid or no longer available"));
        validateAcceptable(invitation);
        return new TenantIdentityApi.InvitationSnapshot(invitation.getEmail(), invitation.getTenant().getName(),
                invitation.getRole().name(), invitation.getExpiresAt());
    }

    @Transactional
    public TenantIdentityApi.AcceptedUserSnapshot acceptInvitationToken(
            String rawToken, String fullName, String passwordHash) {
        Invitation invitation = findInvitation(rawToken, true);
        return acceptLockedInvitation(invitation, fullName, passwordHash);
    }

    private TenantIdentityApi.AcceptedUserSnapshot acceptLockedInvitation(
            Invitation invitation, String fullName, String passwordHash) {
        validateAcceptable(invitation);
        String email = normalizeEmail(invitation.getEmail());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account already exists for this email");
        }

        User user = new User();
        user.setTenant(invitation.getTenant());
        user.setInvitedBy(invitation.getInvitedBy());
        user.setEmail(email);
        user.setFullName(fullName.trim());
        user.setPasswordHash(passwordHash);
        user.setRole(invitation.getRole());
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(LocalDateTime.now());
        invitationRepository.save(invitation);

        audit(invitation.getTenant().getId(), user.getId(), LogAction.USER_INVITATION_ACCEPTED,
                "invitation", invitation.getId(), Map.of("transition", "PENDING->ACCEPTED"));
        invalidateMembers(invitation.getTenant().getId());
        return new TenantIdentityApi.AcceptedUserSnapshot(
                user.getId(), user.getTenant().getId(), user.getEmail(), user.getFullName(),
                user.getRole().name(), user.getStatus().name(),
                user.getTenant().getStatus().name(), user.getPasswordHash());
    }

    @Deprecated
    @Transactional
    public TenantIdentityApi.AcceptedUserSnapshot acceptInvitationHash(
            String tokenHash, String fullName, String passwordHash) {
        Invitation invitation = invitationRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation is invalid or no longer available"));
        return acceptLockedInvitation(invitation, fullName, passwordHash);
    }

    private DirectoryResponse decorateDirectory(DirectoryResponse snapshot, UUID currentUserId, LocalDateTime now) {
        List<MemberResponse> members = snapshot.members().stream()
                .map(member -> new MemberResponse(member.id(), member.email(), member.fullName(), member.role(),
                        member.status(), member.joinedAt(), member.lastLoginAt(), member.id().equals(currentUserId)))
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

    private void invalidateMembers(UUID tenantId) {
        if (businessInvalidationPublisher != null) {
            businessInvalidationPublisher.memberMutation(tenantId);
        }
    }

    private void validateAcceptable(Invitation invitation) {
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ResourceNotFoundException("Invitation is invalid or no longer available");
        }
        if (!invitation.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BadRequestException("Invitation has expired");
        }
    }

    private void ensureNotFinalActiveAdmin(User user) {
        if (user.getStatus() == UserStatus.ACTIVE &&
                userRepository.countByTenant_IdAndRoleAndStatus(
                        user.getTenant().getId(), UserRole.TENANT_ADMIN, UserStatus.ACTIVE) <= 1) {
            throw new BadRequestException("The final active tenant admin cannot be demoted or deactivated");
        }
    }

    private User requireUser(UUID tenantId, UUID userId) {
        return userRepository.findByIdAndTenant_Id(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private Invitation requireInvitation(UUID tenantId, UUID invitationId) {
        return invitationRepository.findByIdAndTenant_Id(invitationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void publishInvitationEmail(Invitation invitation, String token) {
        publishBusinessEvent("tenant.user.invited.v1", new UserInvitedEvent(invitation.getTenant().getId(),
                invitation.getInvitedBy().getId(), invitation.getEmail(), invitation.getTenant().getName(),
                invitation.getRole().name(), token, invitation.getExpiresAt(), invitation.getId(),
                invitation.getStatus().name(), invitation.getCreatedAt() == null
                ? LocalDateTime.now() : invitation.getCreatedAt()));
    }

    private void publishBusinessEvent(String stableType, Object event) {
        if (durableEventPublisher != null) {
            durableEventPublisher.publish(stableType, 1, event);
        } else {
            eventPublisher.publishEvent(event);
        }
    }

    private void audit(UUID tenantId, UUID actorId, LogAction action, String resourceType,
                       UUID resourceId, Map<String, Object> metadata) {
        eventPublisher.publishEvent(AuditLogEvent.builder(this)
                .tenantId(tenantId).userId(actorId).action(action).resourceType(resourceType)
                .resourceId(resourceId).metadata(metadata).build());
    }

    private MemberResponse toMember(User user, UUID currentUserId) {
        return new MemberResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.getStatus(), user.getCreatedAt(), user.getLastLoginAt(),
                currentUserId != null && user.getId().equals(currentUserId));
    }

    private InvitationResponse toInvitation(Invitation invitation) {
        return new InvitationResponse(invitation.getId(), invitation.getEmail(), invitation.getRole(),
                invitation.getStatus(), invitation.getCreatedAt(), invitation.getExpiresAt(),
                invitation.getLastSentAt());
    }

    String hashToken(String token) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String legacyHashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
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
