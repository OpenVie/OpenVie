package com.cacanode.api.tenant.api;

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
