// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.bootstrap.config;

import com.openvie.api.common.event.durable.ModuleEventTypeRegistry;
import com.openvie.api.tenant.api.event.TenantCreatedEvent;
import com.openvie.api.tenant.api.event.UserDeactivatedEvent;
import com.openvie.api.tenant.api.event.UserInvitedEvent;
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
