package com.cacanode.api.bootstrap.config;

import com.cacanode.api.auth.api.event.Login2FARequestedEvent;
import com.cacanode.api.auth.api.event.UserRegisteredEvent;
import com.cacanode.api.common.event.durable.ModuleEventTypeRegistry;
import com.cacanode.api.tenant.api.event.TenantCreatedEvent;
import com.cacanode.api.tenant.api.event.UserDeactivatedEvent;
import com.cacanode.api.tenant.api.event.UserInvitedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class ModuleEventRegistryConfig {
    @Bean
    ModuleEventTypeRegistry moduleEventTypeRegistry() {
        Map<String, Class<?>> types = Map.ofEntries(
                Map.entry("tenant.created.v1", TenantCreatedEvent.class),
                Map.entry("tenant.user.invited.v1", UserInvitedEvent.class),
                Map.entry("tenant.user.deactivated.v1", UserDeactivatedEvent.class),
                Map.entry("auth.user.registered.v1", UserRegisteredEvent.class),
                Map.entry("auth.login-2fa.requested.v1", Login2FARequestedEvent.class)
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
