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

package com.openvie.api.bizapp.service.block;

import com.openvie.api.common.event.BizAppLifecycleEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signed webhook push block. Publishing a Spring event keeps the webhook
 * outbox inside the integration module (same boundary as the workflow audit
 * bridge); the subscriber fan-outs to every matching endpoint after commit.
 */
@Component
@RequiredArgsConstructor
public class WebhookOutBlock implements BizAppBlockExecutor {

    public static final String TYPE = "webhook_out";

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public String supports() {
        return TYPE;
    }

    @Override
    public BlockResult execute(BlockSpec spec, BlockContext context) {
        if (context.recordId() == null) {
            return new BlockResult("webhook_out skipped: no record");
        }
        return new BlockResult("webhook events are lifecycle-driven; see BizAppLifecycleEvent publisher");
    }

    /**
     * Push helper invoked by the record service for lifecycle hooks whose
     * target is {@code webhook_out:<event>}: full kind is
     * {@code bizapp.<appKind>.<event>}.
     */
    public void publish(String appKind, String eventKind, BlockContext context, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", appKind);
        data.put("entity", context.entity());
        if (extra != null) {
            data.putAll(extra);
        }
        eventPublisher.publishEvent(new BizAppLifecycleEvent(
                this, context.orgId(), context.tenantId(),
                "bizapp." + appKind + "." + eventKind, context.recordId(), data));
    }
}
