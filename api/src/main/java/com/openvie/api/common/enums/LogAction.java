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

package com.openvie.api.common.enums;

public enum LogAction {
    SETUP_COMPLETED,
    WORKSPACE_CREATED,
    WORKSPACE_ARCHIVED,
    WORKSPACE_VISIBILITY_CHANGED,
    SELF_REGISTRATION_TOGGLED,
    NOTIFICATION_CHANNEL_CHANGED,
    MEMBER_ADDED,
    MEMBER_REMOVED,
    MEMBER_ROLE_CHANGED,
    USER_INVITE,
    USER_INVITATION_RESENT,
    USER_INVITATION_CANCELLED,
    USER_INVITATION_ACCEPTED,
    USER_ROLE_CHANGED,
    USER_DEACTIVATED,
    USER_REACTIVATED,
    USER_PASSWORD_CHANGED,
    USER_PASSWORD_RESET,
    USER_SSO_LINKED,
    WORKFLOW_STARTED,
    WORKFLOW_TASK_CLAIMED,
    WORKFLOW_APPROVED,
    WORKFLOW_REJECTED,
    WORKFLOW_ESCALATED,
    WORKFLOW_REMINDER_SENT,
    WORKFLOW_SLA_CONFIG_CHANGED,
    API_KEY_CREATED,
    API_KEY_REVOKED,
    WEBHOOK_ENDPOINT_CHANGED,
    WORKFLOW_DEFINITION_DEPLOYED,
    WORKFLOW_PROCESS_COMPLETED
}
