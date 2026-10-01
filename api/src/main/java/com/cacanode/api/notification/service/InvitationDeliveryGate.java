package com.cacanode.api.notification.service;

import com.cacanode.api.common.event.durable.ModuleEventGate;
import com.cacanode.api.tenant.api.DeliveryAvailability;
import com.cacanode.api.tenant.api.event.UserInvitedEvent;
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
