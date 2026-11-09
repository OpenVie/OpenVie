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

package com.openvie.api.bizapp.service.block;

import com.openvie.api.notification.api.NotificationRuntimeApi;
import com.openvie.api.tenant.api.TenantIdentityApi;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotifyBlockTest {

    private final NotificationRuntimeApi notifications = mock(NotificationRuntimeApi.class);
    private final TenantIdentityApi identities = mock(TenantIdentityApi.class);
    private final NotifyBlock block = new NotifyBlock(notifications, identities);
    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @Test
    void resolvesARecordEmailAndRendersSubjectPlaceholders() {
        when(notifications.sendRawEmail(eq(orgId), eq("le.thi.b@company.com"),
                eq("New Purchase Request from Nguyễn Văn A"), contains("New Purchase Request")))
                .thenReturn(true);

        BizAppBlockExecutor.BlockResult result = block.execute(
                new BizAppBlockExecutor.BlockSpec("notifyBlockId", Map.of(
                        "to", java.util.List.of("record.manager_email"),
                        "subject", "New Purchase Request from {{record.requestor}}",
                        "title", "New Purchase Request")),
                context(Map.of(
                        "manager_email", "le.thi.b@company.com",
                        "requestor", "Nguyễn Văn A")));

        assertTrue(result.summary().contains("1 email(s)"));
        verify(notifications).sendRawEmail(eq(orgId), eq("le.thi.b@company.com"),
                eq("New Purchase Request from Nguyễn Văn A"), anyString());
    }

    @Test
    void reportsZeroWhenTheEmailChannelRefusesDelivery() {
        when(notifications.sendRawEmail(eq(orgId), eq("manager@example.com"), anyString(), anyString()))
                .thenReturn(false);

        BizAppBlockExecutor.BlockResult result = block.execute(
                new BizAppBlockExecutor.BlockSpec("notifyBlockId", Map.of(
                        "to", java.util.List.of("manager@example.com"),
                        "subject", "Notice",
                        "title", "Notice")),
                context(Map.of()));

        assertTrue(result.summary().contains("0 email(s)"));
        assertTrue(result.summary().contains("no delivery"));
    }

    @Test
    void doesNotSendWhenTheRecordHasNoResolvableEmail() {
        BizAppBlockExecutor.BlockResult result = block.execute(
                new BizAppBlockExecutor.BlockSpec("notifyBlockId", Map.of(
                        "to", java.util.List.of("record.manager_email"),
                        "subject", "Notice",
                        "title", "Notice")),
                context(Map.of("manager_email", "not-an-email")));

        assertTrue(result.summary().contains("0 email(s)"));
        verify(notifications, never()).sendRawEmail(orgId, "not-an-email", "Notice", "");
    }

    private BizAppBlockExecutor.BlockContext context(Map<String, Object> payload) {
        return new BizAppBlockExecutor.BlockContext(
                orgId, tenantId, actorId, "inventory", "purchase_requests",
                UUID.randomUUID(), payload, "proc", Map.of(), 1);
    }
}
