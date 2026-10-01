package com.cacanode.api.bootstrap;

import com.cacanode.api.tenant.api.RegisterTenantCommand;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.tenant.api.TenantUserResult;
import com.cacanode.api.tenant.repository.TenantRepository;
import com.cacanode.api.tenant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantBootstrapCommandTest {

    private static final String VALID_PASSWORD = "a-strong-bootstrap-password";

    private TenantIdentityApi tenantIdentityApi;
    private TenantRepository tenantRepository;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private MockEnvironment environment;
    private TenantBootstrapCommand command;

    @BeforeEach
    void setUp() {
        tenantIdentityApi = mock(TenantIdentityApi.class);
        tenantRepository = mock(TenantRepository.class);
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        environment = new MockEnvironment();
        command = new TenantBootstrapCommand(
                tenantIdentityApi, tenantRepository, userRepository, passwordEncoder, environment);

        when(passwordEncoder.encode(anyString())).thenAnswer(invocation -> "hash:" + invocation.getArgument(0));
        when(tenantRepository.count()).thenReturn(0L);
        when(userRepository.count()).thenReturn(0L);
        when(tenantIdentityApi.registerTenantWithAdmin(any())).thenReturn(
                TenantUserResult.builder()
                        .tenantId(UUID.randomUUID())
                        .userId(UUID.randomUUID())
                        .email("admin@example.com")
                        .role("TENANT_ADMIN")
                        .status("ACTIVE")
                        .build());
    }

    @Test
    void refusesToRunWithoutConfiguration() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> command.run(null));

        assertTrue(failure.getMessage().contains(TenantBootstrapCommand.OPTION_TENANT_NAME));
        assertTrue(failure.getMessage().contains(TenantBootstrapCommand.ENV_TENANT_NAME));
        verify(tenantIdentityApi, never()).registerTenantWithAdmin(any());
    }

    @Test
    void refusesToRunWhenAnyTenantOrUserAlreadyExists() {
        givenBootstrapValuesFromEnvironment();
        when(tenantRepository.count()).thenReturn(0L);
        when(userRepository.count()).thenReturn(1L);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> command.run(null));

        assertTrue(failure.getMessage().contains("already contains tenants or users"));
        verify(tenantIdentityApi, never()).registerTenantWithAdmin(any());
        verify(tenantIdentityApi, never()).activateUser(any());
    }

    @Test
    void refusesKnownDevelopmentPassword() {
        givenBootstrapValuesFromEnvironment();
        environment.setProperty(TenantBootstrapCommand.OPTION_ADMIN_PASSWORD,
                TenantBootstrapCommand.KNOWN_DEV_PASSWORD);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> command.run(null));

        assertTrue(failure.getMessage().contains("development seed password"));
        verify(tenantIdentityApi, never()).registerTenantWithAdmin(any());
    }

    @Test
    void provisionsTenantAndActivatesAdminOnEmptyStore() {
        givenBootstrapValuesFromEnvironment();
        UUID userId = UUID.randomUUID();
        when(tenantIdentityApi.registerTenantWithAdmin(any())).thenReturn(
                TenantUserResult.builder()
                        .tenantId(UUID.randomUUID())
                        .userId(userId)
                        .email("admin@example.com")
                        .role("TENANT_ADMIN")
                        .status("ACTIVE")
                        .build());

        command.run(null);

        ArgumentCaptor<RegisterTenantCommand> captor = ArgumentCaptor.forClass(RegisterTenantCommand.class);
        verify(tenantIdentityApi).registerTenantWithAdmin(captor.capture());
        assertEquals("Example Workspace", captor.getValue().getCompanyName());
        assertEquals("admin@example.com", captor.getValue().getEmail());
        assertEquals("hash:" + VALID_PASSWORD, captor.getValue().getPasswordHash());
        verify(tenantIdentityApi).activateUser(userId);
    }

    @Test
    void commandLineOptionWinsOverEnvironmentVariable() {
        givenBootstrapValuesFromEnvironment();
        environment.setProperty(TenantBootstrapCommand.ENV_TENANT_NAME, "Environment Workspace");
        environment.setProperty(TenantBootstrapCommand.OPTION_TENANT_NAME, "Option Workspace");

        command.run(null);

        ArgumentCaptor<RegisterTenantCommand> captor = ArgumentCaptor.forClass(RegisterTenantCommand.class);
        verify(tenantIdentityApi).registerTenantWithAdmin(captor.capture());
        assertEquals("Option Workspace", captor.getValue().getCompanyName());
    }

    private void givenBootstrapValuesFromEnvironment() {
        environment.setProperty(TenantBootstrapCommand.ENV_TENANT_NAME, "Example Workspace");
        environment.setProperty(TenantBootstrapCommand.ENV_ADMIN_EMAIL, "admin@example.com");
        environment.setProperty(TenantBootstrapCommand.ENV_ADMIN_PASSWORD, VALID_PASSWORD);
    }
}
