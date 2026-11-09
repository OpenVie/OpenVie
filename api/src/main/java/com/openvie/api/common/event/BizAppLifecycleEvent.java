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

package com.openvie.api.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.Map;
import java.util.UUID;

/**
 * BizApp lifecycle/record event raised by the bizapp module.
 *
 * <p>The integration module subscribes after commit (the bridge pattern used
 * for workflow events) and turns this into a signed webhook delivery for every
 * matching endpoint — BizApp blocks never touch the webhook outbox themselves.
 */
@Getter
public class BizAppLifecycleEvent extends ApplicationEvent {

    private final UUID orgId;
    private final UUID tenantId;
    private final String eventType;
    private final UUID sourceId;
    private final Map<String, Object> data;

    public BizAppLifecycleEvent(Object source, UUID orgId, UUID tenantId, String eventType,
            UUID sourceId, Map<String, Object> data) {
        super(source);
        this.orgId = orgId;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.sourceId = sourceId;
        this.data = data;
    }
}
