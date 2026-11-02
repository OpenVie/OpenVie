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

package com.openvie.api.notification.api;

import java.util.UUID;

/**
 * Published boundary: best-effort transactional email for other modules.
 *
 * <p>BizApp notification blocks render their own HTML; this contract sends it
 * through the organization's configured channel without exposing the channel
 * resolver or provider internals. Delivery failures return {@code false} —
 * they never break the caller's transaction.
 */
public interface NotificationRuntimeApi {

    /**
     * @return true when the message left through a configured channel
     */
    boolean sendRawEmail(UUID orgId, String toEmail, String subject, String htmlBody);
}
