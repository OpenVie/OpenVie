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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmtpEmailProviderTest {

    private final SmtpEmailProvider provider = new SmtpEmailProvider();

    @Test
    void providerMetadata() {
        assertEquals("smtp", provider.type());
        assertEquals("SMTP", provider.providerName());
    }

    @Test
    void availabilityChecksHost() {
        NotificationChannelConfig noHost = new NotificationChannelConfig(
                "smtp", "from@example.com", "OpenVie", "", 587, "", "", false, false);
        assertFalse(provider.available(noHost));

        NotificationChannelConfig withHost = new NotificationChannelConfig(
                "smtp", "from@example.com", "OpenVie", "localhost", 1025, "", "", false, false);
        assertTrue(provider.available(withHost));
    }

    @Test
    void sendThrowsWhenTransportUnavailable() {
        NotificationChannelConfig invalidConfig = new NotificationChannelConfig(
                "smtp", "from@example.com", "OpenVie", "invalid.non-existent.local", 65530, "", "", false, false);
        EmailMessage message = new EmailMessage(
                "to@example.com", "to@example.com", "Test", "<img src=\"cid:openvie-logo\" />");

        assertThrows(EmailDeliveryException.class, () -> provider.send(message, invalidConfig));
    }
}
