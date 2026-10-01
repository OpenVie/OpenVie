package com.cacanode.api.bootstrap.config;

import com.cacanode.api.common.event.durable.ModuleEventTypeRegistry;
import com.cacanode.api.tenant.api.event.TenantCreatedEvent;
import com.cacanode.api.tenant.api.event.UserDeactivatedEvent;
import com.cacanode.api.tenant.api.event.UserInvitedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Registry of durable module events. Login-2FA and welcome-email events are
 * gone: email is optional and login no longer issues a challenge.
 */
@Configuration
public class ModuleEventRegistryConfig {
    @Bean
    ModuleEventTypeRegistry moduleEventTypeRegistry() {
        Map<String, Class<?>> types = Map.of(
                "tenant.created.v1", TenantCreatedEvent.class,
                "tenant.user.invited.v1", UserInvitedEvent.class,
                "tenant.user.deactivated.v1", UserDeactivatedEvent.class
        );
        return (stableType, version) -> {
            Class<?> type = types.get(stableType);
            if (type == null || version != 1) {
                throw new IllegalArgumentException("Unknown module event type: " + stableType + " v" + version);
            }
            return type;
        };
    }
}
