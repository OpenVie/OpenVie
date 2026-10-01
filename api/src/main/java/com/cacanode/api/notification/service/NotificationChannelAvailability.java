package com.cacanode.api.notification.service;

import com.cacanode.api.notification.repository.NotificationChannelRepository;
import com.cacanode.api.tenant.api.DeliveryAvailability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Single source of truth for "can this organization send email right now?".
 *
 * <p>Email is optional in this product: an installation with no configured
 * channel still boots, logs in, ingests, and answers. Only the features whose
 * value depends on delivery — invitations — consult this. Implements the
 * tenant-published {@link DeliveryAvailability} port so the tenant module
 * never imports the notification implementation.
 *
 * <p>{@link #enabledFor} mirrors {@link NotificationChannelResolver}: an
 * enabled stored channel or a usable environment bootstrap default.
 * {@link #configuredFor} additionally accepts a stored-but-disabled channel,
 * because invitations minted against it are held in the outbox and replay when
 * the channel is switched on.
 */
@Service
@RequiredArgsConstructor
public class NotificationChannelAvailability implements DeliveryAvailability {

    private final NotificationChannelRepository channelRepository;
    private final NotificationChannelResolver resolver;

    @Override
    @Transactional(readOnly = true)
    public boolean enabledFor(UUID orgId) {
        if (orgId == null) {
            return false;
        }
        try {
            return resolver.resolve(orgId)
                    .filter(resolved -> resolved.provider().available(resolved.config()))
                    .isPresent();
        } catch (EmailDeliveryException unusable) {
            // A stored channel exists but cannot be decrypted (key rotated or
            // unset). Treat delivery as unavailable; the resolver has logged
            // the reason and the invitation will be held, not lost.
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean configuredFor(UUID orgId) {
        return orgId != null
                && (channelRepository.existsByOrgId(orgId) || enabledFor(orgId));
    }
}
