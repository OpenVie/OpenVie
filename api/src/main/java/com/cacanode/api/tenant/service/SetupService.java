package com.cacanode.api.tenant.service;

import com.cacanode.api.common.enums.LogAction;
import com.cacanode.api.common.event.AuditLogEvent;
import com.cacanode.api.common.exception.custom.ConflictException;
import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.TenantCredentials;
import com.cacanode.api.tenant.api.TenantStatus;
import com.cacanode.api.tenant.api.UserStatus;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One-time installation setup: creates the organization, its default workspace,
 * and the owner account that claimed it.
 *
 * <p>Setup is available only while no account exists and closes permanently on
 * first success. Concurrent submissions are serialized by a transaction-scoped
 * advisory lock, so exactly one wins and the rest are refused rather than
 * silently ignored.
 */
@Service
@Slf4j(topic = "SETUP")
@RequiredArgsConstructor
public class SetupService {

    private static final String DEFAULT_WORKSPACE_NAME = "General";
    private static final String DEFAULT_WORKSPACE_SLUG = "general";

    private final OrganizationRepository organizationRepository;
    private final TenantRepository workspaceRepository;
    private final UserRepository userRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final TenantWorkspaceService provisioning;
    private final TenantCredentials credentials;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public boolean required() {
        return userRepository.count() == 0;
    }

    @Transactional
    public SetupResult complete(SetupCommand command) {
        // Serializes concurrent submissions; the loser blocks here, then the
        // count check below sees the committed winner and refuses.
        organizationRepository.lockSetup();
        if (userRepository.count() > 0 || organizationRepository.count() > 0) {
            throw new ConflictException("This installation has already been set up");
        }
        String passwordHash = credentials.hashNewPassword(command.password());

        Organization organization = new Organization();
        organization.setName(requireText(command.organizationName(), "Organization name is required"));
        organization.setSlug(uniqueOrganizationSlug(command.organizationName()));
        organization.setAllowSelfRegistration(command.allowSelfRegistration());
        organizationRepository.save(organization);

        Tenant workspace = new Tenant();
        workspace.setOrganization(organization);
        workspace.setName(DEFAULT_WORKSPACE_NAME);
        workspace.setSlug(DEFAULT_WORKSPACE_SLUG);
        workspace.setStatus(TenantStatus.ACTIVE);
        workspace.setVisibility(WorkspaceVisibility.PUBLIC);
        workspace.setDefaultWorkspace(true);
        workspaceRepository.save(workspace);

        User owner = new User();
        owner.setOrganization(organization);
        owner.setEmail(command.email().trim().toLowerCase(Locale.ROOT));
        owner.setPasswordHash(passwordHash);
        owner.setFullName(requireText(command.fullName(), "Your name is required"));
        owner.setRole(OrgRole.ORG_OWNER);
        owner.setStatus(UserStatus.ACTIVE);
        userRepository.save(owner);

        WorkspaceMember membership = new WorkspaceMember();
        membership.setUser(owner);
        membership.setWorkspace(workspace);
        membership.setRole(WorkspaceRole.WORKSPACE_ADMIN);
        memberRepository.save(membership);

        provisioning.provisionWorkspaceAssets(workspace);

        eventPublisher.publishEvent(AuditLogEvent.builder(this)
                .tenantId(workspace.getId())
                .userId(owner.getId())
                .action(LogAction.SETUP_COMPLETED)
                .resourceType("organization")
                .resourceId(organization.getId())
                .metadata(Map.of("workspace", workspace.getSlug()))
                .build());

        log.info("Installation set up: orgId={}, workspaceId={}, owner={}",
                organization.getId(), workspace.getId(), owner.getEmail());
        return new SetupResult(organization.getId(), workspace.getId(), owner.getId());
    }

    private String requireText(String value, String message) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new com.cacanode.api.common.exception.custom.BadRequestException(message);
        }
        return trimmed;
    }

    private String uniqueOrganizationSlug(String name) {
        String base = slugify(name);
        String slug = base.isBlank() ? "organization" : base;
        int suffix = 1;
        while (organizationRepository.existsBySlug(slug)) {
            slug = base + "-" + suffix++;
        }
        return slug;
    }

    /** Accent-folded, lowercase, hyphen-separated identifier. */
    static String slugify(String value) {
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD);
        return java.util.regex.Pattern.compile("\\p{InCOMBINING_DIACRITICAL_MARKS}+")
                .matcher(normalized)
                .replaceAll("")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    public record SetupCommand(
            String organizationName,
            String fullName,
            String email,
            String password,
            boolean allowSelfRegistration) {
    }

    public record SetupResult(UUID organizationId, UUID workspaceId, UUID ownerId) {
    }
}
