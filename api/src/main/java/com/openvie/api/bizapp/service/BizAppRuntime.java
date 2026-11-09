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

package com.openvie.api.bizapp.service;

import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.common.event.AuditLogEvent;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.event.BizAppLifecycleEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Everything the BizApp module needs from the completion of one block/hook
 * run: the parsed manifest, the installation row, and prebuilt audit/event
 * helpers so block executors never touch persistence directly.
 */
@Service
@RequiredArgsConstructor
public class BizAppRuntime {

    private final ApplicationEventPublisher eventPublisher;

    @Getter
    @RequiredArgsConstructor
    public static class AppContext {
        private final BizAppInstallation installation;
        private final Manifest manifest;
    }

    public UUID orgId(BizAppInstallation installation) {
        return installation.getOrgId();
    }

    public void audit(UUID tenantId, UUID userId, LogAction action, String resourceType,
            UUID resourceId, Map<String, Object> metadata) {
        eventPublisher.publishEvent(AuditLogEvent.builder(this)
                .tenantId(tenantId)
                .userId(userId)
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .metadata(metadata == null ? Map.of() : metadata)
                .build());
    }

    /** Lifecycle webhook event; delivered after commit through the bridge. */
    public void lifecycle(UUID orgId, UUID tenantId, String eventType,
            UUID sourceId, Map<String, Object> data) {
        eventPublisher.publishEvent(new BizAppLifecycleEvent(this, orgId, tenantId, eventType, sourceId, data));
    }
}
