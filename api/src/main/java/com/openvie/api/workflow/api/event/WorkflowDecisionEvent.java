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

package com.openvie.api.workflow.api.event;

import java.util.UUID;

/**
 * Published by the workflow module the moment an approval process settles, so
 * the notification module can tell the submitter without the workflow module
 * ever reaching into another module's internals.
 *
 * <p>It carries the recipient's resolved address so the consumer needs no
 * lookup of its own; a {@code null} {@code recipientEmail} means the account no
 * longer resolves and the notice is skipped.
 *
 * <p>{@code decision} is the terminal verdict only — {@code APPROVE} or
 * {@code REJECT}; a hand-off to level 2 is not a decision and is reported
 * through the audit trail instead.
 */
public record WorkflowDecisionEvent(
        UUID organizationId,
        UUID workspaceId,
        UUID processInstanceId,
        UUID documentId,
        String documentFileName,
        UUID recipientUserId,
        String recipientEmail,
        String recipientName,
        String decision,
        String reason,
        int approvalLevel,
        String publishTarget) {
}
