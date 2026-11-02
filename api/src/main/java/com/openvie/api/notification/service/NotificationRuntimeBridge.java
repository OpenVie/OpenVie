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

import com.openvie.api.notification.api.NotificationRuntimeApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Implements the published {@link NotificationRuntimeApi} boundary on top of
 * the email service so other modules never see channel resolution internals.
 */
@Service
@RequiredArgsConstructor
public class NotificationRuntimeBridge implements NotificationRuntimeApi {

    private final EmailService emailService;

    @Override
    public boolean sendRawEmail(UUID orgId, String toEmail, String subject, String htmlBody) {
        return emailService.sendRawEmail(orgId, toEmail, subject, htmlBody);
    }
}
