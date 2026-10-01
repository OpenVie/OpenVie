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
 */
@Service
@RequiredArgsConstructor
public class NotificationChannelAvailability implements DeliveryAvailability {

    private final NotificationChannelRepository channelRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean enabledFor(UUID orgId) {
        return orgId != null && channelRepository.existsByOrgIdAndEnabledTrue(orgId);
    }
}
