package com.cacanode.api.tenant.service;

import com.cacanode.api.common.enums.LogAction;
import com.cacanode.api.common.event.AuditLogEvent;
import com.cacanode.api.common.exception.custom.BadRequestException;
import com.cacanode.api.common.exception.custom.ConflictException;
import com.cacanode.api.common.exception.custom.ResourceNotFoundException;
import com.cacanode.api.common.exception.custom.UnauthorizedException;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.tenant.api.TenantIdentityApi.IdentitySnapshot;
import com.cacanode.api.tenant.api.TenantStatus;
import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.WorkspaceRole;
import com.cacanode.api.tenant.enums.WorkspaceVisibility;
import com.cacanode.api.tenant.model.Organization;
import com.cacanode.api.tenant.model.Tenant;
import com.cacanode.api.tenant.model.User;
import com.cacanode.api.tenant.model.WorkspaceMember;
import com.cacanode.api.tenant.repository.OrganizationRepository;
import com.cacanode.api.tenant.repository.TenantRepository;
import com.cacanode.api.tenant.repository.UserRepository;
import com.cacanode.api.tenant.repository.WorkspaceMemberRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Organization-level workspace lifecycle: create, rename, archive, toggle
 * visibility, self-registration, and cross-workspace membership. Only an
 * ORG_OWNER reaches these paths; workspace admins manage members inside their
 * own workspace through {@link TenantUserManagementService}.
 */
@Service
@Slf4j(topic = "WORKSPACE")
@RequiredArgsConstructor
public class WorkspaceService {

    private final OrganizationRepository organizationRepository;
    private final TenantRepository workspaceRepository;
    private final UserRepository userRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final TenantWorkspaceService provisioning;
    private final TenantIdentityApi identityApi;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public OrganizationSnapshot requireOrg(UUID orgId) {
        Organization organization = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization was not found"));
        return new OrganizationSnapshot(
                organization.getId(), organization.getName(), organization.getSlug(),
                organization.isAllowSelfRegistration());
    }

    /** Every workspace in the organization, for the owner's admin view. */
    @Transactional(readOnly = true)
    public List<WorkspaceView> listAll(UUID actorUserId) {
        IdentitySnapshot actor = requireOrgOwner(actorUserId);
        return workspaceRepository.findByOrganization_IdOrderByCreatedAtAsc(actor.orgId()).stream()
                .map(workspace -> toView(workspace, memberCount(workspace.getId())))
                .toList();
    }

    @Transactional
    public WorkspaceView create(UUID actorUserId, String name, String visibility) {
        IdentitySnapshot actor = requireOrgOwner(actorUserId);
        String trimmed = requireName(name);
        Organization organization = requireOrganization(actor.orgId());

        Tenant workspace = new Tenant();
        workspace.setOrganization(organization);
        workspace.setName(trimmed);
        workspace.setSlug(uniqueSlug(actor.orgId(), trimmed));
        workspace.setStatus(TenantStatus.ACTIVE);
        workspace.setVisibility(parseVisibility(visibility));
        workspace.setDefaultWorkspace(false);
        workspaceRepository.save(workspace);
        provisioning.provisionWorkspaceAssets(workspace);

        WorkspaceMember membership = new WorkspaceMember();
        membership.setUser(requireUser(actorUserId));
        membership.setWorkspace(workspace);
        membership.setRole(WorkspaceRole.WORKSPACE_ADMIN);
        memberRepository.save(membership);

        audit(actorUserId, LogAction.WORKSPACE_CREATED, workspace.getId(),
                Map.of("slug", workspace.getSlug()));
        log.info("Workspace created: orgId={}, workspaceId={}, by={}",
                actor.orgId(), workspace.getId(), actorUserId);
        return toView(workspace, 1);
    }

    @Transactional
    public WorkspaceView rename(UUID actorUserId, UUID workspaceId, String name) {
        IdentitySnapshot actor = requireOrgOwner(actorUserId);
        Tenant workspace = requireWorkspace(actor.orgId(), workspaceId);
        workspace.setName(requireName(name));
        workspaceRepository.save(workspace);
        audit(actorUserId, LogAction.WORKSPACE_CREATED, workspaceId, Map.of("rename", true));
        return toView(workspace, memberCount(workspaceId));
    }

    @Transactional
    public WorkspaceView setVisibility(UUID actorUserId, UUID workspaceId, String visibility) {
        IdentitySnapshot actor = requireOrgOwner(actorUserId);
        Tenant workspace = requireWorkspace(actor.orgId(), workspaceId);
        WorkspaceVisibility parsed = parseVisibility(visibility);
        if (workspace.isDefaultWorkspace() && parsed == WorkspaceVisibility.PRIVATE) {
            throw new BadRequestException("The default workspace must stay public");
        }
        workspace.setVisibility(parsed);
        workspaceRepository.save(workspace);
        audit(actorUserId, LogAction.WORKSPACE_VISIBILITY_CHANGED, workspaceId,
                Map.of("visibility", parsed.name()));
        return toView(workspace, memberCount(workspaceId));
    }

    /**
     * Archives (soft-deletes) a workspace. Derived indexes are not touched here:
     * the row is retained so audit history stays attributable, and hard removal
     * of a workspace and its indexes is a separate reviewed operation.
     */
    @Transactional
    public void archive(UUID actorUserId, UUID workspaceId) {
        IdentitySnapshot actor = requireOrgOwner(actorUserId);
        Tenant workspace = requireWorkspace(actor.orgId(), workspaceId);
        if (workspace.isDefaultWorkspace()) {
            throw new BadRequestException("The default workspace cannot be archived");
        }
        if (workspace.getStatus() == TenantStatus.ARCHIVED) {
            return;
        }
        workspace.setStatus(TenantStatus.ARCHIVED);
        workspaceRepository.save(workspace);
        audit(actorUserId, LogAction.WORKSPACE_ARCHIVED, workspaceId, Map.of());
        log.info("Workspace archived: workspaceId={}, by={}", workspaceId, actorUserId);
    }

    @Transactional
    public void setSelfRegistration(UUID actorUserId, boolean allowed) {
        IdentitySnapshot actor = requireOrgOwner(actorUserId);
        Organization organization = requireOrganization(actor.orgId());
        organization.setAllowSelfRegistration(allowed);
        organizationRepository.save(organization);
        audit(actorUserId, LogAction.SELF_REGISTRATION_TOGGLED, organization.getId(),
                Map.of("allowed", allowed));
    }

    /** Adds an existing account in this organization to a workspace. */
    @Transactional
    public MemberView addMember(UUID actorUserId, UUID workspaceId, String email, WorkspaceRole role) {
        IdentitySnapshot actor = requireOrgOwner(actorUserId);
        Tenant workspace = requireWorkspace(actor.orgId(), workspaceId);
        User user = userRepository.findByEmailIgnoreCase(email.trim())
                .filter(candidate -> candidate.getOrganization().getId().equals(actor.orgId()))
                .orElseThrow(() -> new ResourceNotFoundException("No account exists for that email"));
        if (memberRepository.findByUser_IdAndWorkspace_Id(user.getId(), workspaceId).isPresent()) {
            throw new ConflictException("That account is already a member of this workspace");
        }
        WorkspaceMember membership = new WorkspaceMember();
        membership.setUser(user);
        membership.setWorkspace(workspace);
        membership.setRole(role);
        memberRepository.save(membership);
        audit(actorUserId, LogAction.MEMBER_ADDED, user.getId(), Map.of("role", role.name()));
        return toMember(user, membership);
    }

    @Transactional
    public void removeMember(UUID actorUserId, UUID workspaceId, UUID userId) {
        IdentitySnapshot actor = requireOrgOwner(actorUserId);
        requireWorkspace(actor.orgId(), workspaceId);
        if (actorUserId.equals(userId)) {
            throw new BadRequestException("You cannot remove yourself from this workspace");
        }
        WorkspaceMember membership = memberRepository
                .findByUser_IdAndWorkspace_IdForUpdate(userId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("That account is not a member here"));
        if (membership.getRole() == WorkspaceRole.WORKSPACE_ADMIN && adminCount(workspaceId) <= 1) {
            throw new BadRequestException("A workspace needs at least one active admin");
        }
        memberRepository.delete(membership);
        audit(actorUserId, LogAction.MEMBER_REMOVED, userId, Map.of());
    }

    @Transactional
    public MemberView changeMemberRole(
            UUID actorUserId, UUID workspaceId, UUID userId, WorkspaceRole role) {
        IdentitySnapshot actor = requireOrgOwner(actorUserId);
        requireWorkspace(actor.orgId(), workspaceId);
        WorkspaceMember membership = memberRepository
                .findByUser_IdAndWorkspace_IdForUpdate(userId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("That account is not a member here"));
        if (membership.getRole() == WorkspaceRole.WORKSPACE_ADMIN
                && role != WorkspaceRole.WORKSPACE_ADMIN && adminCount(workspaceId) <= 1) {
            throw new BadRequestException("A workspace needs at least one active admin");
        }
        WorkspaceRole previous = membership.getRole();
        membership.setRole(role);
        memberRepository.save(membership);
        audit(actorUserId, LogAction.MEMBER_ROLE_CHANGED, userId,
                Map.of("from", previous.name(), "to", role.name()));
        return toMember(membership.getUser(), membership);
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(UUID actorUserId, UUID workspaceId) {
        IdentitySnapshot actor = requireOrgOwner(actorUserId);
        requireWorkspace(actor.orgId(), workspaceId);
        return memberRepository.findByWorkspace_IdOrderByRoleAscCreatedAtAsc(workspaceId).stream()
                .map(membership -> toMember(membership.getUser(), membership))
                .toList();
    }

    /**
     * Resolves the caller's organization identity and refuses anyone who is not
     * the organization owner. Workspace targeting then goes through
     * {@link #requireWorkspace}, which validates organization ownership.
     */
    private IdentitySnapshot requireOrgOwner(UUID userId) {
        IdentitySnapshot identity = identityApi.findUserById(userId);
        if (identity == null) {
            throw new UnauthorizedException("Session is no longer valid");
        }
        if (identity.orgRole() != OrgRole.ORG_OWNER) {
            throw new UnauthorizedException("Only the organization owner can manage workspaces");
        }
        return identity;
    }

    private Tenant requireWorkspace(UUID orgId, UUID workspaceId) {
        Tenant workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace was not found"));
        if (!workspace.getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("Workspace was not found");
        }
        if (workspace.getStatus() == TenantStatus.ARCHIVED) {
            throw new BadRequestException("This workspace is archived");
        }
        return workspace;
    }

    private Organization requireOrganization(UUID orgId) {
        return organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization was not found"));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            throw new BadRequestException("Workspace name must be 1-255 characters");
        }
        return trimmed;
    }

    private String uniqueSlug(UUID orgId, String name) {
        String base = SetupService.slugify(name);
        if (base.isBlank()) {
            base = "workspace";
        }
        String slug = base;
        int suffix = 1;
        while (workspaceRepository.existsByOrganization_IdAndSlug(orgId, slug)) {
            slug = base + "-" + suffix++;
        }
        return slug;
    }

    private WorkspaceVisibility parseVisibility(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return WorkspaceVisibility.PUBLIC;
        }
        try {
            return WorkspaceVisibility.valueOf(visibility.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Visibility must be PUBLIC or PRIVATE");
        }
    }

    private int memberCount(UUID workspaceId) {
        return memberRepository.findByWorkspace_IdOrderByRoleAscCreatedAtAsc(workspaceId).size();
    }

    private long adminCount(UUID workspaceId) {
        return memberRepository.countByWorkspace_IdAndRole(workspaceId, WorkspaceRole.WORKSPACE_ADMIN);
    }

    private WorkspaceView toView(Tenant workspace, int members) {
        return WorkspaceView.builder()
                .id(workspace.getId())
                .name(workspace.getName())
                .slug(workspace.getSlug())
                .status(workspace.getStatus().name())
                .visibility(workspace.getVisibility().name())
                .isDefault(workspace.isDefaultWorkspace())
                .memberCount(members)
                .createdAt(workspace.getCreatedAt())
                .build();
    }

    private MemberView toMember(User user, WorkspaceMember membership) {
        return MemberView.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .status(user.getStatus().name())
                .workspaceRole(membership.getRole().name())
                .joinedAt(membership.getCreatedAt())
                .build();
    }

    private void audit(UUID actorId, LogAction action, UUID resourceId, Map<String, Object> metadata) {
        eventPublisher.publishEvent(AuditLogEvent.builder(this)
                .userId(actorId).action(action)
                .resourceType("workspace").resourceId(resourceId).metadata(metadata).build());
    }

    public record OrganizationSnapshot(UUID id, String name, String slug, boolean allowSelfRegistration) {
    }

    @Getter
    @Builder
    public static class WorkspaceView {
        private UUID id;
        private String name;
        private String slug;
        private String status;
        private String visibility;
        private boolean isDefault;
        private int memberCount;
        private java.time.LocalDateTime createdAt;
    }

    @Getter
    @Builder
    public static class MemberView {
        private UUID userId;
        private String email;
        private String fullName;
        private String status;
        private String workspaceRole;
        private java.time.LocalDateTime joinedAt;
    }
}
