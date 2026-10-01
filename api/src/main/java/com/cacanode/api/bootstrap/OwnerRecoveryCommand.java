package com.cacanode.api.bootstrap;

import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.TenantCredentials;
import com.cacanode.api.tenant.api.UserStatus;
import com.cacanode.api.tenant.model.User;
import com.cacanode.api.tenant.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Offline recovery of organization-owner access.
 *
 * <p>Email is optional and login is password-only, so a lost owner password is
 * a lockout with no self-service path. This command is the documented
 * recovery. Its guards exist so it cannot become an account-creation or
 * privilege-escalation path:
 *
 * <ul>
 *   <li>the account must already exist — recovery never creates one;</li>
 *   <li>it must be an ACTIVE organization owner — recovery never grants or
 *       transfers ownership, and an inactive account could not log in anyway;</li>
 *   <li>both values must be supplied explicitly — no defaults, no generated
 *       passwords written to logs.</li>
 * </ul>
 *
 * <p>Enabled only with {@code --app.command.mode=recover-owner}. Values come
 * from {@code --recover-owner-email} / {@code --recover-owner-password} or the
 * {@code RECOVER_OWNER_EMAIL} / {@code RECOVER_OWNER_PASSWORD} variables.
 */
@Component
@ConditionalOnProperty(name = "app.command.mode", havingValue = "recover-owner")
@RequiredArgsConstructor
@Slf4j(topic = "OWNER-RECOVERY")
public class OwnerRecoveryCommand implements ApplicationRunner {

    static final String OPTION_EMAIL = "recover-owner-email";
    static final String OPTION_PASSWORD = "recover-owner-password";
    static final String ENV_EMAIL = "RECOVER_OWNER_EMAIL";
    static final String ENV_PASSWORD = "RECOVER_OWNER_PASSWORD";

    private final UserRepository userRepository;
    private final TenantCredentials credentials;
    private final Environment environment;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = require(OPTION_EMAIL, ENV_EMAIL).trim().toLowerCase(Locale.ROOT);
        String password = require(OPTION_PASSWORD, ENV_PASSWORD);

        User target = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalStateException(
                        "No account exists for " + email
                        + ". Recovery resets an existing owner's password; it cannot"
                        + " create an account or transfer ownership."));
        if (target.getRole() != OrgRole.ORG_OWNER) {
            throw new IllegalStateException(
                    email + " is not an organization owner. Recovery only resets owner"
                    + " credentials; promote a member through the API instead.");
        }
        if (target.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalStateException(
                    email + " is not an active account, so there is no owner access to"
                    + " recover. Reactivate it through the API instead.");
        }

        // The new password is validated and hashed by the credential gate, and
        // the account is flagged to change it on next login.
        credentials.setInitialPassword(target.getId(), target.getId(), password);
        log.info("Recovered owner access for {} (email only; no credential logged)", email);
    }

    private String require(String option, String envVar) {
        String fromOption = value(option);
        if (!fromOption.isEmpty()) {
            return fromOption;
        }
        String fromEnv = value(envVar);
        if (!fromEnv.isEmpty()) {
            return fromEnv;
        }
        throw new IllegalStateException(
                "Missing recovery value: pass --" + option + "=... or set " + envVar);
    }

    private String value(String key) {
        String value = environment.getProperty(key);
        return value == null ? "" : value.trim();
    }
}
