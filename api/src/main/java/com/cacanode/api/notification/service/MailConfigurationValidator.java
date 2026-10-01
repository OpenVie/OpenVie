package com.cacanode.api.notification.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails application startup with the exact configuration keys and environment
 * variables needed when the selected mail provider is not configured. This is
 * the "clear failure instead of silently skipping delivery" contract: a missing
 * mail configuration is an operator error, never a silently dropped email.
 */
@Component
public class MailConfigurationValidator implements InitializingBean {

    private static final List<String> PROVIDERS = List.of("sendgrid", "smtp", "brevo");

    private final Environment environment;

    public MailConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();

        String provider = property("app.email.provider", "sendgrid").trim().toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(provider)) {
            problems.add("app.email.provider='" + provider + "' is not supported; set MAIL_PROVIDER to one of "
                    + PROVIDERS);
        } else {
            validateProvider(provider, "app.email.provider", problems);
        }

        if (property("app.email.from-email", "").isBlank()) {
            problems.add("app.email.from-email is required; set FROM_EMAIL to the address mail is sent from");
        }

        String fallback = property("app.email.fallback-provider", "").trim().toLowerCase(Locale.ROOT);
        if (!fallback.isEmpty()) {
            if (!PROVIDERS.contains(fallback)) {
                problems.add("app.email.fallback-provider='" + fallback + "' is not supported; set "
                        + "MAIL_FALLBACK_PROVIDER to one of " + PROVIDERS + " or leave it empty");
            } else if (!fallback.equals(provider)) {
                validateProvider(fallback, "app.email.fallback-provider", problems);
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Mail configuration is incomplete:\n - "
                    + String.join("\n - ", problems));
        }
    }

    private void validateProvider(String provider, String property, List<String> problems) {
        switch (provider) {
            case "sendgrid" -> require(property, "spring.sendgrid.api-key", "SENDGRID_API_KEY", problems);
            case "brevo" -> require(property, "spring.brevo.api-key", "BREVO_API_KEY", problems);
            case "smtp" -> require(property, "spring.mail.host", "MAIL_HOST", problems);
            default -> { }
        }
    }

    private void require(String property, String key, String envVar, List<String> problems) {
        if (property(key, "").isBlank()) {
            problems.add(property + " requires " + key + " to be set; set " + envVar);
        }
    }

    private String property(String key, String defaultValue) {
        try {
            return environment.getProperty(key, defaultValue);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            // An unresolvable ${VAR} placeholder means the variable is required but
            // unset; report it as missing so the operator gets the variable name.
            return defaultValue;
        }
    }
}
