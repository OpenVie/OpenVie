package com.cacanode.api.tenant.service;

import com.cacanode.api.auth.repository.RefreshTokenRepository;
import com.cacanode.api.auth.service.AuthService;
import com.cacanode.api.auth.service.JwtService;
import com.cacanode.api.common.cache.*;
import com.cacanode.api.common.config.CacheProperties;
import com.cacanode.api.tenant.enums.InvitationStatus;
import com.cacanode.api.tenant.enums.UserRole;
import com.cacanode.api.tenant.enums.UserStatus;
import com.cacanode.api.tenant.model.Invitation;
import com.cacanode.api.tenant.model.Tenant;
import com.cacanode.api.tenant.model.User;
import com.cacanode.api.tenant.repository.InvitationRepository;
import com.cacanode.api.tenant.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Phase2DirectoryCacheTest {
    @Test
    void cachedTenantSnapshotDecoratesViewerAndExpiryPerRequest() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        User first = user(tenant, "first@example.com");
        User second = user(tenant, "second@example.com");
        Invitation invitation = new Invitation();
        invitation.setId(UUID.randomUUID());
        invitation.setTenant(tenant);
        invitation.setInvitedBy(first);
        invitation.setEmail("pending@example.com");
        invitation.setRole(UserRole.USER);
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setCreatedAt(LocalDateTime.now().minusDays(4));
        invitation.setLastSentAt(LocalDateTime.now().minusDays(4));
        invitation.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        UserRepository users = mock(UserRepository.class);
        InvitationRepository invitations = mock(InvitationRepository.class);
        when(users.findByTenant_IdOrderByFullNameAsc(tenantId)).thenReturn(List.of(first, second));
        when(invitations.findByTenant_IdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of(invitation));
        TenantUserManagementService service = new TenantUserManagementService(
                users, invitations, mock(ApplicationEventPublisher.class));
        CacheProperties properties = new CacheProperties();
        properties.setEnabled(true);
        properties.setBusinessReadEnabled(true);
        properties.setUserDirectoryEnabled(true);
        ReflectionTestUtils.setField(service, "businessCache", new VersionedJsonCache(
                new MemoryStore(), properties, mock(CacheMetrics.class),
                new ObjectMapper().findAndRegisterModules()));
        ReflectionTestUtils.setField(service, "cacheKeyFactory", new CacheKeyFactory("ccn:v1"));

        var firstView = service.getDirectory(tenantId, first.getId());
        var secondView = service.getDirectory(tenantId, second.getId());

        assertTrue(firstView.members().getFirst().currentUser());
        assertFalse(firstView.members().get(1).currentUser());
        assertFalse(secondView.members().getFirst().currentUser());
        assertTrue(secondView.members().get(1).currentUser());
        assertEquals(InvitationStatus.EXPIRED, firstView.invitations().getFirst().status());
        assertEquals(InvitationStatus.EXPIRED, secondView.invitations().getFirst().status());
        verify(users, times(1)).findByTenant_IdOrderByFullNameAsc(tenantId);
        verify(invitations, times(1)).findByTenant_IdOrderByCreatedAtDesc(tenantId);
    }

    private static User user(Tenant tenant, String email) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenant(tenant);
        user.setEmail(email);
        user.setFullName(email);
        user.setPasswordHash("hash");
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(LocalDateTime.now().minusDays(1));
        return user;
    }

    private static final class MemoryStore implements CacheStore {
        private final Map<String, byte[]> values = new HashMap<>();
        public CacheReadResult get(String cacheName, String key) {
            byte[] value = values.get(key);
            return value == null ? CacheReadResult.of(CacheReadStatus.MISS) : CacheReadResult.hit(value);
        }
        public CacheOperationStatus put(String cacheName, String key, byte[] value, Duration ttl) {
            values.put(key, value.clone());
            return CacheOperationStatus.SUCCESS;
        }
        public CacheOperationStatus delete(String cacheName, String key) {
            values.remove(key);
            return CacheOperationStatus.SUCCESS;
        }
    }
}
