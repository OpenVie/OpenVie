package com.cacanode.api.bootstrap;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.TenantCredentials;
import com.cacanode.api.tenant.api.UserStatus;
import com.cacanode.api.tenant.model.Organization;
import com.cacanode.api.tenant.model.User;
import com.cacanode.api.tenant.repository.UserRepository;

/**
 * Offline owner recovery: resets an existing active owner's password and
 * refuses to become an account-creation or privilege-escalation path.
 *
 * <p>Both the {@code --recover-owner-*} options and the {@code RECOVER_OWNER_*}
 * variables reach the command through the Spring {@code Environment}; that
 * resolution is property-source behavior, so these tests exercise it through
 * the environment directly.
 */
class OwnerRecoveryCommandTest {

    private UserRepository userRepository;
    private TenantCredentials credentials;
    private MockEnvironment environment;
    private OwnerRecoveryCommand command;

    private Organization organization;
    private User owner;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        credentials = mock(TenantCredentials.class);
        environment = new MockEnvironment();
        command = new OwnerRecoveryCommand(userRepository, credentials, environment);

        organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName("Acme Corp");
        owner = user("ada@acme.test", OrgRole.ORG_OWNER, UserStatus.ACTIVE);
    }

    @Test
    void resetsAnExistingOwnersPassword() {
        givenValues("ada@acme.test", "a-recovered-long-password");
        when(userRepository.findByEmailIgnoreCase("ada@acme.test")).thenReturn(Optional.of(owner));

        command.run(noArgs());

        verify(credentials).setInitialPassword(
                owner.getId(), owner.getId(), "a-recovered-long-password");
    }

    @Test
    void emailIsNormalizedBeforeLookup() {
        givenValues("  ADA@Acme.Test ", "a-recovered-long-password");
        when(userRepository.findByEmailIgnoreCase("ada@acme.test")).thenReturn(Optional.of(owner));

        command.run(noArgs());

        verify(credentials).setInitialPassword(any(), any(), any());
    }

    @Test
    void refusesWhenNoAccountExists() {
        givenValues("ghost@acme.test", "a-recovered-long-password");
        when(userRepository.findByEmailIgnoreCase("ghost@acme.test")).thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> command.run(noArgs()));
        assertTrue(exception.getMessage().contains("cannot create an account"),
                "recovery must not mint accounts: " + exception.getMessage());
        verify(credentials, never()).setInitialPassword(any(), any(), any());
    }

    @Test
    void refusesToResetANonOwnerAccount() {
        User member = user("member@acme.test", OrgRole.MEMBER, UserStatus.ACTIVE);
        givenValues("member@acme.test", "a-recovered-long-password");
        when(userRepository.findByEmailIgnoreCase("member@acme.test")).thenReturn(Optional.of(member));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> command.run(noArgs()));
        assertTrue(exception.getMessage().contains("not an organization owner"),
                "recovery must not escalate members: " + exception.getMessage());
        verify(credentials, never()).setInitialPassword(any(), any(), any());
    }

    @Test
    void refusesAnInactiveAccount() {
        User suspended = user("ada@acme.test", OrgRole.ORG_OWNER, UserStatus.SUSPENDED);
        givenValues("ada@acme.test", "a-recovered-long-password");
        when(userRepository.findByEmailIgnoreCase("ada@acme.test")).thenReturn(Optional.of(suspended));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> command.run(noArgs()));
        assertTrue(exception.getMessage().contains("not an active account"),
                "an inactive account cannot log in even after a reset: " + exception.getMessage());
        verify(credentials, never()).setInitialPassword(any(), any(), any());
    }

    @Test
    void missingValuesAreReportedByName() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> command.run(noArgs()));
        assertTrue(exception.getMessage().contains(OwnerRecoveryCommand.OPTION_EMAIL),
                "the operator must be told which variable to set: " + exception.getMessage());
    }

    @Test
    void weakPasswordIsRejectedByTheCredentialGate() {
        givenValues("ada@acme.test", "short");
        when(userRepository.findByEmailIgnoreCase("ada@acme.test")).thenReturn(Optional.of(owner));
        org.mockito.Mockito.doThrow(new com.cacanode.api.common.exception.custom.BadRequestException(
                        "Password must be at least 12 characters"))
                .when(credentials).setInitialPassword(any(), any(), org.mockito.ArgumentMatchers.eq("short"));

        assertThrows(com.cacanode.api.common.exception.custom.BadRequestException.class,
                () -> command.run(noArgs()));
    }

    private void givenValues(String email, String password) {
        environment.setProperty(OwnerRecoveryCommand.ENV_EMAIL, email);
        environment.setProperty(OwnerRecoveryCommand.ENV_PASSWORD, password);
    }

    private DefaultApplicationArguments noArgs() {
        return new DefaultApplicationArguments(new String[0]);
    }

    private User user(String email, OrgRole role, UserStatus status) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setOrganization(organization);
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setRole(role);
        user.setStatus(status);
        return user;
    }
}
