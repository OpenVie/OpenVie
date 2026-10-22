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

package com.openvie.api.tenant.api;

import java.util.UUID;

/**
 * Asks whether outbound delivery is possible for an organization.
 *
 * <p>Owned by the tenant module so that features whose value depends on
 * delivery (invitations) can refuse cleanly without importing the notification
 * implementation. The notification module provides the implementation.
 */
public interface DeliveryAvailability {

    /** True when the organization has an <em>enabled</em> notification
     *  channel: mail would go out right now. */
    boolean enabledFor(UUID orgId);

    /** True when the organization has <em>any</em> notification channel,
     *  enabled or not: the operator has set one up, so invitations can be
     *  minted and held until the channel is switched on. */
    boolean configuredFor(UUID orgId);
}
