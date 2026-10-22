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

package com.openvie.api.notification.service;

import com.openvie.api.common.event.durable.ModuleEventGate;
import com.openvie.api.tenant.api.DeliveryAvailability;
import com.openvie.api.tenant.api.event.UserInvitedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Holds invitation mail in the outbox while the organization has no enabled
 * notification channel, and replays it the moment one is switched on. The
 * precondition is a deployment state, not a delivery failure, so held events
 * never consume retry budget.
 */
@Component
@RequiredArgsConstructor
public class InvitationDeliveryGate implements ModuleEventGate {

    private final DeliveryAvailability availability;

    @Override
    public boolean readyFor(Object payload) {
        return !(payload instanceof UserInvitedEvent event)
                || availability.enabledFor(event.orgId());
    }

    @Override
    public String unavailableReason(Object payload) {
        if (payload instanceof UserInvitedEvent event) {
            return "Organization '" + event.organizationName()
                    + "' has no enabled notification channel; the invitation email is held"
                    + " and will be sent when one is configured.";
        }
        return ModuleEventGate.super.unavailableReason(payload);
    }
}
