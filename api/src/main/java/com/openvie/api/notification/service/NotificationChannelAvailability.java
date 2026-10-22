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

import com.openvie.api.notification.repository.NotificationChannelRepository;
import com.openvie.api.tenant.api.DeliveryAvailability;
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
