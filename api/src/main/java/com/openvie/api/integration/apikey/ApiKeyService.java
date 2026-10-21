package com.openvie.api.integration.apikey;

import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.event.AuditLogEvent;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.integration.IntegrationCredentials;
import com.openvie.api.tenant.api.TenantIdentityApi;
import com.openvie.api.tenant.api.TenantIdentityApi.MembershipSnapshot;
import com.openvie.api.tenant.service.TenantUserManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Lifecycle of machine-to-machine credentials.
 *
 * <p>Creating a key provisions a real service-member user (unusable password)
 * plus a workspace membership, so every downstream foreign key — uploads,
 * sessions, audit — records a user id. The plaintext secret is returned exactly
 * once; storage keeps only the SHA-256 hash.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "API-KEY")
public class ApiKeyService {

    private static final String KEY_PREFIX_MARKER = "ovk_";
    private static final String SERVICE_EMAIL_DOMAIN = "keys.openvie.invalid";

    private final ApiKeyRepository keyRepository;
    private final TenantUserManagementService userManagementService;
    private final TenantIdentityApi identityApi;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ApiKeyDtos.CreatedApiKey create(UUID orgId, UUID creatorId, ApiKeyDtos.CreateApiKeyRequest request) {
        if (request.expiresAt() != null && !request.expiresAt().isAfter(LocalDateTime.now())) {
            throw new BadRequestException("Expiry must be in the future");
        }
        // Provisioning path owned by the org-account administration rules; it
        // validates that the workspace belongs to this same organization.
        TenantIdentityApi.IdentitySnapshot serviceUser = userManagementService.provisionServiceAccount(
                orgId, creatorId, request.workspaceId(), request.role(), request.name().trim());
        ApiKey key = new ApiKey();
        key.setOrgId(orgId);
        key.setTenantId(request.workspaceId());
        key.setUserId(serviceUser.userId());
        key.setName(request.name().trim());
        key.setRole(request.role());
        key.setCreatedBy(creatorId);
        key.setExpiresAt(request.expiresAt());

        String plaintext = KEY_PREFIX_MARKER + randomSecret();
        key.setKeyPrefix(KEY_PREFIX_MARKER + plaintext.substring(KEY_PREFIX_MARKER.length(),
                KEY_PREFIX_MARKER.length() + 8));
        key.setKeyHash(sha256Hex(plaintext));
        keyRepository.save(key);

        eventPublisher.publishEvent(AuditLogEvent.builder(this)
                .tenantId(request.workspaceId())
                .userId(creatorId)
                .action(LogAction.API_KEY_CREATED)
                .resourceType("ApiKey")
                .resourceId(key.getId())
                .metadata(java.util.Map.of(
                        "name", request.name().trim(),
                        "workspaceId", request.workspaceId().toString(),
                        "serviceUserId", serviceUser.userId().toString()))
                .build());

        log.info("Api key created id={} name={} workspace={}", key.getId(), key.getName(), key.getTenantId());
        return new ApiKeyDtos.CreatedApiKey(key.getId(), plaintext, toView(key));
    }

    /** Resolves the live membership of the key's service user; null when the key cannot authenticate. */
    @Transactional(readOnly = true)
    public MembershipSnapshot authenticate(String presented) {
        ApiKey key = findByPlaintext(presented);
        if (key == null) {
            return null;
        }
        MembershipSnapshot membership = identityApi.requireMembership(key.getUserId(), key.getTenantId());
        touch(key);
        return membership;
    }

    @Transactional(readOnly = true)
    public List<ApiKeyDtos.ApiKeyView> list(UUID orgId) {
        return keyRepository.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public void revoke(UUID orgId, UUID actorId, UUID keyId) {
        ApiKey key = keyRepository.findById(keyId)
                .filter(keyRow -> keyRow.getOrgId().equals(orgId))
                .orElseThrow(() -> new ResourceNotFoundException("Api key not found"));
        if (!"REVOKED".equals(key.getStatus())) {
            key.setStatus("REVOKED");
            userManagementService.deactivateServiceAccount(orgId, key.getUserId(), actorId);
            eventPublisher.publishEvent(AuditLogEvent.builder(this)
                    .tenantId(key.getTenantId())
                    .userId(actorId)
                    .action(LogAction.API_KEY_REVOKED)
                    .resourceType("ApiKey")
                    .resourceId(key.getId())
                    .metadata(java.util.Map.of("name", key.getName()))
                    .build());
            log.info("Api key revoked id={} name={}", key.getId(), key.getName());
        }
    }

    private ApiKey findByPlaintext(String presented) {
        if (presented == null || !presented.startsWith(KEY_PREFIX_MARKER)
                || presented.length() <= KEY_PREFIX_MARKER.length() + 8) {
            return null;
        }
        ApiKey key = keyRepository
                .findByKeyHashAndStatus(sha256Hex(presented.trim()), "ACTIVE")
                .orElse(null);
        if (key == null) {
            return null;
        }
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.info("Api key expired id={}", key.getId());
            return null;
        }
        return key;
    }

    /**
     * Strictly rate-throttled bookkeeping: at most one update every 30 s per key
     * so high-frequency integrations do not hammer the database.
     */
    private void touch(ApiKey key) {
        LocalDateTime now = LocalDateTime.now();
        if (key.getLastUsedAt() == null || key.getLastUsedAt().isBefore(now.minusSeconds(30))) {
            // The request thread holds no long transaction here; a tiny update
            // on the hot path is the accepted trade for fresh last-used data.
            key.setLastUsedAt(now);
            keyRepository.save(key);
        }
    }

    private ApiKeyDtos.ApiKeyView toView(ApiKey key) {
        boolean expired = !"ACTIVE".equals(key.getStatus())
                || (key.getExpiresAt() != null && key.getExpiresAt().isBefore(LocalDateTime.now()));
        return new ApiKeyDtos.ApiKeyView(
                key.getId(),
                key.getTenantId(),
                key.getUserId(),
                key.getName(),
                key.maskedKey(),
                key.getRole(),
                key.getStatus(),
                key.getCreatedAt(),
                key.getLastUsedAt(),
                key.getExpiresAt(),
                key.getCreatedBy(),
                expired);
    }

    private String randomSecret() {
        return IntegrationCredentials.randomSecret(IntegrationCredentials.KEY_MARKER)
                .substring(IntegrationCredentials.KEY_MARKER.length());
    }

    private String sha256Hex(String value) {
        return IntegrationCredentials.sha256Hex(value);
    }
}
