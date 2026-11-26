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

package com.openvie.api.integration.api;

import java.util.Map;
import java.util.UUID;

/**
 * Published boundary for journaling workspace-scoped integration events.
 *
 * <p>The producer and the webhook transport stay decoupled: business modules
 * publish through this contract without importing the webhook outbox or its
 * repositories.
 */
public interface WebhookPublisherApi {

    /**
     * Journals one event for every active endpoint of the workspace's
     * organization. Implementations must be best-effort and must not fail the
     * caller's transaction.
     */
    void publish(UUID workspaceId, String eventType, UUID sourceEventId, Map<String, Object> data);
}
