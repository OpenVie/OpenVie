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

/**
 * A mail transport implementation.
 *
 * <p>This is the plugin seam: implementations are in-repo Spring beans keyed by
 * {@link #type()}, and an organization's {@code notification_channels} row
 * supplies the credentials at send time. A private deployment adds a transport
 * by contributing another bean of this type; nothing else changes.
 *
 * <p>{@link #available()} lets a transport report that it is present but not
 * usable (for example SMTP selected with no host configured) so the service can
 * fail with an actionable message instead of a null-pointer.
 */
public interface EmailProvider {

    /** Stable lowercase key matching {@code notification_channels.type}. */
    String type();

    /** Human-readable name for logs and error messages. */
    String providerName();

    /** True when this transport can currently accept a send. */
    default boolean available(NotificationChannelConfig config) {
        return true;
    }

    void send(EmailMessage message, NotificationChannelConfig config) throws EmailDeliveryException;
}
