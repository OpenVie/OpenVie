package com.cacanode.api.tenant.api;

import java.util.UUID;

/**
 * Asks whether outbound delivery is currently possible for an organization.
 *
 * <p>Owned by the tenant module so that features whose value depends on
 * delivery (invitations) can refuse cleanly without importing the notification
 * implementation. The notification module provides the implementation.
 */
public interface DeliveryAvailability {

    /** True when the organization has an enabled notification channel. */
    boolean enabledFor(UUID orgId);
}
