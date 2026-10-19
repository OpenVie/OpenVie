package com.openvie.api.tenant.api.event;

import java.util.UUID;

/**
 * Notice that an organization administrator created an account for the
 * recipient. Best-effort: delivery is attempted after the transaction
 * commits and failures never undo the account creation. The temporary
 * password is never carried here — the administrator shares it directly,
 * and the first sign-in forces a password change.
 */
public record AccountCreatedEvent(
        UUID orgId,
        UUID userId,
        String email,
        String fullName
) {
}
