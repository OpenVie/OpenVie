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
 * Webhook-only companion to {@link BizAppLifecycleEvent}.
 *
 * <p>Record lifecycle events are always published as
 * {@link BizAppLifecycleEvent} because projection and CDC consume every write.
 * Webhook delivery is opt-in per manifest ({@code webhook_out.events}), so the
 * producer raises this second event only when the frozen manifest selects the
 * event. The integration bridge subscribes to this type and never filters the
 * domain stream.
 */
@Getter
public class BizAppWebhookEvent extends ApplicationEvent {

    private final UUID orgId;
    private final UUID tenantId;
    private final String eventType;
    private final UUID sourceId;
    private final Map<String, Object> data;

    public BizAppWebhookEvent(Object source, UUID orgId, UUID tenantId, String eventType,
            UUID sourceId, Map<String, Object> data) {
        super(source);
        this.orgId = orgId;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.sourceId = sourceId;
        this.data = data;
    }
}
