package com.cacanode.api.bootstrap;

import com.cacanode.api.tenant.api.RegisterTenantCommand;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.tenant.api.TenantUserResult;
import com.cacanode.api.tenant.repository.TenantRepository;
import com.cacanode.api.tenant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Operator-only, one-time tenant bootstrap for a fresh install.
 *
 * <p>Enabled only with {@code --app.command.mode=bootstrap-tenant}. Values come
 * from the CLI options {@code --bootstrap-tenant-name},
 * {@code --bootstrap-admin-email} and {@code --bootstrap-admin-password},
 * falling back to the {@code BOOTSTRAP_TENANT_NAME},
 * {@code BOOTSTRAP_ADMIN_EMAIL} and {@code BOOTSTRAP_ADMIN_PASSWORD}
 * environment variables. There is no default credential: the command refuses to
 * run with a missing value or a known development password, and refuses to run
 * at all once any tenant or user exists, so it can never silently overwrite an
 * existing installation.
 */
@Component
@ConditionalOnProperty(name = "app.command.mode", havingValue = "bootstrap-tenant")
@RequiredArgsConstructor
@Slf4j(topic = "TENANT-BOOTSTRAP")
public class TenantBootstrapCommand implements ApplicationRunner {

    static final String OPTION_TENANT_NAME = "bootstrap-tenant-name";
    static final String OPTION_ADMIN_EMAIL = "bootstrap-admin-email";
    static final String OPTION_ADMIN_PASSWORD = "bootstrap-admin-password";

    static final String ENV_TENANT_NAME = "BOOTSTRAP_TENANT_NAME";
    static final String ENV_ADMIN_EMAIL = "BOOTSTRAP_ADMIN_EMAIL";
    static final String ENV_ADMIN_PASSWORD = "BOOTSTRAP_ADMIN_PASSWORD";

    static final int MIN_PASSWORD_LENGTH = 12;
    /** Development seed password from db/seed/dev; never acceptable here. */
    static final String KNOWN_DEV_PASSWORD = "Cacanode@123";
    static final String ADMIN_FULL_NAME = "Tenant Administrator";

    private final TenantIdentityApi tenantIdentityApi;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        String tenantName = require(OPTION_TENANT_NAME, ENV_TENANT_NAME);
        String adminEmail = require(OPTION_ADMIN_EMAIL, ENV_ADMIN_EMAIL);
        String adminPassword = require(OPTION_ADMIN_PASSWORD, ENV_ADMIN_PASSWORD);
        validatePassword(adminPassword);

        if (tenantRepository.count() > 0 || userRepository.count() > 0) {
            throw new IllegalStateException(
                    "Refusing to bootstrap: this database already contains tenants or users. "
                            + "Bootstrap is a one-time operation on an empty installation; "
                            + "invite additional administrators through the API instead.");
        }

        TenantUserResult result = tenantIdentityApi.registerTenantWithAdmin(
                RegisterTenantCommand.builder()
                        .companyName(tenantName)
                        .email(adminEmail)
                        .fullName(ADMIN_FULL_NAME)
                        .passwordHash(passwordEncoder.encode(adminPassword))
                        .build());

        // Registration leaves the admin user PENDING because e-mail verification
        // cannot complete here; login requires ACTIVE.
        tenantIdentityApi.activateUser(result.getUserId());

        log.info(
                "Bootstrapped tenant '{}' (tenantId={}) with active administrator {} (userId={})",
                tenantName, result.getTenantId(), adminEmail, result.getUserId());
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
                "Missing bootstrap value: pass --" + option + "=... or set " + envVar);
    }

    private String value(String key) {
        String value = environment.getProperty(key);
        return value == null ? "" : value.trim();
    }

    private void validatePassword(String password) {
        List<String> problems = new ArrayList<>();
        if (password.length() < MIN_PASSWORD_LENGTH) {
            problems.add("must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.equals(KNOWN_DEV_PASSWORD)) {
            problems.add("is the published development seed password and must not be used");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "The bootstrap admin password " + String.join(" and ", problems)
                            + ". Choose a fresh credential for " + OPTION_ADMIN_PASSWORD
                            + " / " + ENV_ADMIN_PASSWORD);
        }
    }
}
