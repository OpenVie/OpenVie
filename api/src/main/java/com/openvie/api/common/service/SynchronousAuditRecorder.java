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

package com.openvie.api.common.service;

import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.model.AuditLog;
import com.openvie.api.common.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SynchronousAuditRecorder {
    private final AuditLogRepository repository;

    public void record(UUID tenantId, UUID actorId, LogAction action, String resourceType,
                       UUID resourceId, String ipAddress, String userAgent,
                       Map<String, Object> metadata) {
        AuditLog value = new AuditLog();
        value.setTenantId(tenantId);
        value.setUserId(actorId);
        value.setAction(action);
        value.setResourceType(resourceType);
        value.setResourceId(resourceId);
        value.setIpAddress(ipAddress);
        value.setUserAgent(userAgent);
        value.setMetadata(metadata == null ? Map.of() : Map.copyOf(metadata));
        repository.save(value);
    }
}
