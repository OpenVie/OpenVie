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
 * Decrypted, in-memory mail settings for one delivery. Values come either from
 * an enabled {@code notification_channels} row (organization settings, stored
 * encrypted) or from the environment as the bootstrap default.
 *
 * <p>Never log this record and never serialize it into an API response: it
 * carries the SMTP relay password.
 *
 * @param type       provider type: {@code smtp}
 * @param fromEmail  envelope sender address
 * @param fromName   display name of the sender
 * @param host       SMTP host
 * @param port       SMTP port
 * @param username   SMTP auth user, blank when the relay needs no auth
 * @param password   SMTP auth password
 * @param smtpAuth   whether SMTP AUTH is used
 * @param starttls   whether STARTTLS is negotiated
 */
public record NotificationChannelConfig(
        String type,
        String fromEmail,
        String fromName,
        String host,
        int port,
        String username,
        String password,
        boolean smtpAuth,
        boolean starttls) {

    public boolean isSmtp() {
        return "smtp".equalsIgnoreCase(type);
    }
}
