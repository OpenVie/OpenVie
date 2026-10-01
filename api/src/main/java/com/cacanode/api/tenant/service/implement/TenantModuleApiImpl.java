package com.cacanode.api.tenant.service.implement;

import com.cacanode.api.tenant.api.event.TenantCreatedEvent;
import com.cacanode.api.common.cache.BusinessCacheInvalidationPublisher;
import com.cacanode.api.common.exception.custom.ResourceNotFoundException;
import com.cacanode.api.common.event.durable.DurableEventPublisher;
import com.cacanode.api.tenant.api.RegisterTenantCommand;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.tenant.api.TenantUserResult;
import com.cacanode.api.tenant.api.UserAuthDto;
import com.cacanode.api.tenant.api.TenantStatus;
import com.cacanode.api.tenant.enums.UserRole;
import com.cacanode.api.tenant.enums.UserStatus;
import com.cacanode.api.tenant.model.Tenant;
import com.cacanode.api.tenant.model.User;
import com.cacanode.api.tenant.repository.TenantRepository;
import com.cacanode.api.tenant.repository.UserRepository;
import com.cacanode.api.tenant.service.TenantWorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Slf4j(topic = "TENANT-API")
@RequiredArgsConstructor
public class TenantModuleApiImpl implements TenantIdentityApi {

        private final PasswordEncoder passwordEncoder;
        private final TenantRepository tenantRepository;
        private final UserRepository userRepository;
        private final com.cacanode.api.tenant.repository.InvitationRepository invitationRepository;
        private final com.cacanode.api.tenant.service.TenantUserManagementService userManagementService;
        private final TenantWorkspaceService tenantWorkspaceService;
        private final ApplicationEventPublisher eventPublisher;
        @Autowired(required = false)
        private BusinessCacheInvalidationPublisher businessInvalidationPublisher;
        @Autowired(required = false)
        private DurableEventPublisher durableEventPublisher;

        @Override
        @Transactional
        public TenantUserResult registerTenantWithAdmin(RegisterTenantCommand command) {

                // 1. Create tenant
                Tenant tenant = new Tenant();
                tenant.setName(command.getCompanyName());
                tenant.setSlug(generateSlug(command.getCompanyName()));
                tenant.setStatus(TenantStatus.ACTIVE);
                tenant.setMaxDocuments(50);
                tenant.setMaxMessages(10_000);
                tenant.setMaxTeamMembers(5);
                tenant.setMaxStorageMb(10_240);
                tenantRepository.save(tenant);
                tenantWorkspaceService.provisionDefaultWorkspace(tenant);

                // 2. Create admin user - tenant module owns users table
                User user = new User();
                user.setTenant(tenant);
                user.setEmail(command.getEmail());
                user.setPasswordHash(command.getPasswordHash());
                user.setFullName(command.getFullName());
                user.setRole(UserRole.TENANT_ADMIN);
                user.setStatus(UserStatus.PENDING);
                userRepository.save(user);
                publishBusinessEvent("tenant.created.v1", new TenantCreatedEvent(
                                tenant.getId(), user.getId(), tenant.getName(),
                                tenant.getStatus().name(), tenant.getCreatedAt()));

                log.info("Tenant and admin user created: tenantId={}, userId={}", tenant.getId(), user.getId());

                // 3. Return result - no entity crosses the boundary
                return TenantUserResult.builder()
                                .tenantId(tenant.getId())
                                .userId(user.getId())
                                .email(user.getEmail())
                                .role(user.getRole().name())
                                .status(tenant.getStatus().name())
                                .build();
        }

        @Override
        @Transactional
        public TenantUserResult authenticateUser(String email, String password) {
                return userRepository.findByEmail(email)
                                .filter(user -> passwordEncoder.matches(password, user.getPasswordHash()))
                                .map(user -> TenantUserResult.builder()
                                                .tenantId(user.getTenant().getId())
                                                .userId(user.getId())
                                                .email(user.getEmail())
                                                .fullName(user.getFullName())
                                                .role(user.getRole().name())
                                                .status(user.getStatus().name())
                                                .build())
                                .orElse(null);
        }

        @Override
        public UserAuthDto findUserByEmail(String email) {
                return userRepository.findByEmail(email)
                                .map(user -> UserAuthDto.builder()
                                                .userId(user.getId())
                                                .tenantId(user.getTenant().getId())
                                                .email(user.getEmail())
                                                .fullName(user.getFullName())
                                                .passwordHash(user.getPasswordHash())
                                                .role(user.getRole().name())
                                                .status(user.getStatus().name())
                                                .tenantStatus(user.getTenant().getStatus().name())
                                                .build())
                                .orElse(null);
        }

        @Override
        public UserAuthDto findUserById(UUID userId) {
                return userRepository.findById(userId)
                                .map(user -> UserAuthDto.builder()
                                                .userId(user.getId())
                                                .tenantId(user.getTenant().getId())
                                                .email(user.getEmail())
                                                .fullName(user.getFullName())
                                                .passwordHash(user.getPasswordHash())
                                                .role(user.getRole().name())
                                                .status(user.getStatus().name())
                                                .tenantStatus(user.getTenant().getStatus().name())
                                                .build())
                                .orElse(null);
        }

        @Override
        public boolean existsByEmail(String email) {
                return userRepository.existsByEmail(email);
        }

        @Override
        @Transactional
        public UserAuthDto activateUser(UUID userId) {
                User user = userRepository.findById(userId)
                                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

                user.setStatus(UserStatus.ACTIVE);
                userRepository.save(user);

                log.info("User activated: userId={}, email={}", userId, user.getEmail());

                return UserAuthDto.builder()
                                .userId(user.getId())
                                .tenantId(user.getTenant().getId())
                                .email(user.getEmail())
                                .fullName(user.getFullName())
                                .passwordHash(user.getPasswordHash())
                                .role(user.getRole().name())
                                .status(user.getStatus().name())
                                .tenantStatus(user.getTenant().getStatus().name())
                                .build();
        }

        @Override
        @Transactional
        public void suspendUser(UUID userId) {
                User user = userRepository.findById(userId)
                                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

                user.setStatus(UserStatus.SUSPENDED);
                userRepository.save(user);

                log.info("User suspended due to verification abuse: userId={}, email={}", userId, user.getEmail());
        }

        @Override
        @Transactional(readOnly = true)
        public TenantSnapshot getTenant(UUID tenantId) {
                Tenant tenant = tenantRepository.findById(tenantId)
                                .orElseThrow(() -> new ResourceNotFoundException("Tenant was not found"));
                return new TenantSnapshot(tenant.getId(), tenant.getName());
        }

        @Override
        @Transactional(readOnly = true)
        public UserSnapshot requireUser(UUID tenantId, UUID userId) {
                return userRepository.findByIdAndTenant_Id(userId, tenantId)
                                .map(this::userSnapshot)
                                .orElseThrow(() -> new ResourceNotFoundException("User was not found"));
        }

        @Override
        @Transactional(readOnly = true)
        public java.util.List<UserSnapshot> listUsers(UUID tenantId) {
                return userRepository.findByTenant_IdOrderByFullNameAsc(tenantId).stream()
                                .map(this::userSnapshot).toList();
        }

        @Override
        public InvitationSnapshot validateInvitation(String rawToken) {
                return userManagementService.validateInvitationToken(rawToken);
        }

        @Override
        public AcceptedUserSnapshot acceptInvitation(String rawToken, String fullName, String passwordHash) {
                return userManagementService.acceptInvitationToken(rawToken, fullName, passwordHash);
        }

        @Override
        @Transactional(readOnly = true)
        public long memberUsage(UUID tenantId, LocalDateTime now) {
                return userRepository.countByTenant_IdAndStatus(tenantId, UserStatus.ACTIVE)
                                + invitationRepository.countByTenant_IdAndStatusAndExpiresAtAfter(
                                tenantId, com.cacanode.api.tenant.enums.InvitationStatus.PENDING, now);
        }

        private UserSnapshot userSnapshot(User user) {
                return new UserSnapshot(user.getId(), user.getTenant().getId(), user.getFullName(),
                                user.getEmail(), user.getRole().name(), user.getStatus().name());
        }

        private String generateSlug(String companyName) {
                String normalized = Normalizer.normalize(companyName, Normalizer.Form.NFD);
                Pattern pattern = Pattern.compile("\\p{InCOMBINING_DIACRITICAL_MARKS}+");
                String slug = pattern.matcher(normalized)
                                .replaceAll("")
                                .toLowerCase(Locale.ROOT)
                                .replaceAll("[^a-z0-9\\s-]", "")
                                .replaceAll("[\\s]+", "-")
                                .trim();

                // Ensure uniqueness by appending random suffix if slug exists
                if (tenantRepository.existsBySlug(slug)) {
                        slug = slug + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
                }

                return slug;
        }

        private void publishBusinessEvent(String stableType, Object event) {
                if (durableEventPublisher != null) {
                        durableEventPublisher.publish(stableType, 1, event);
                } else {
                        eventPublisher.publishEvent(event);
                }
        }

}
