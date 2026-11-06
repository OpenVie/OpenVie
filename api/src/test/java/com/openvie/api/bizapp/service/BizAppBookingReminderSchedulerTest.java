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

package com.openvie.api.bizapp.service;

import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppRecordRepository;
import com.openvie.api.notification.api.NotificationRuntimeApi;
import com.openvie.api.tenant.api.TenantIdentityApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BizAppBookingReminderSchedulerTest {

    private BizAppRecordRepository recordRepository;
    private NotificationRuntimeApi notificationRuntimeApi;
    private TenantIdentityApi tenantIdentityApi;
    private BizAppProperties properties;
    private BizAppBookingReminderScheduler scheduler;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID creatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        recordRepository = mock(BizAppRecordRepository.class);
        notificationRuntimeApi = mock(NotificationRuntimeApi.class);
        tenantIdentityApi = mock(TenantIdentityApi.class);
        properties = mock(BizAppProperties.class);
        when(properties.enabled()).thenReturn(true);

        scheduler = new BizAppBookingReminderScheduler(
                recordRepository,
                notificationRuntimeApi,
                tenantIdentityApi,
                properties
        );
    }

    @Test
    void isWithin24HoursEvaluatesDateOnlyCorrectly() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 6, 10, 0);

        // Tomorrow is within 24-48h calendar window
        assertTrue(BizAppBookingReminderScheduler.isWithin24Hours("2026-11-07", now));
        // Today is within window
        assertTrue(BizAppBookingReminderScheduler.isWithin24Hours("2026-11-06", now));
        // Day after tomorrow is not yet within 24h
        assertFalse(BizAppBookingReminderScheduler.isWithin24Hours("2026-11-08", now));
        // Past is not within window
        assertFalse(BizAppBookingReminderScheduler.isWithin24Hours("2026-11-05", now));
        // Invalid or null
        assertFalse(BizAppBookingReminderScheduler.isWithin24Hours(null, now));
        assertFalse(BizAppBookingReminderScheduler.isWithin24Hours("", now));
        assertFalse(BizAppBookingReminderScheduler.isWithin24Hours("not-a-date", now));
    }

    @Test
    void isWithin24HoursEvaluatesDateTimeCorrectly() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 6, 10, 0);

        // 14h from now: target is 2026-11-07 00:00 -> within 24h
        assertTrue(BizAppBookingReminderScheduler.isWithin24Hours("2026-11-07T00:00", now));
        // 20h from now: target is 2026-11-07 06:00 -> within 24h
        assertTrue(BizAppBookingReminderScheduler.isWithin24Hours("2026-11-07T06:00", now));
        // 25h from now: target is 2026-11-07 11:00 -> NOT within 24h yet
        assertFalse(BizAppBookingReminderScheduler.isWithin24Hours("2026-11-07T11:00", now));
        // In the past (1h ago):
        assertFalse(BizAppBookingReminderScheduler.isWithin24Hours("2026-11-06T09:00", now));
    }

    @Test
    void processRecordSendsInAppNotificationAndEmailsAndMarksRuntime() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 6, 10, 0);
        BizAppRecord record = new BizAppRecord();
        record.setId(UUID.randomUUID());
        record.setTenantId(tenantId);
        record.setOrgId(orgId);
        record.setCreatedBy(creatorId);
        record.setAppKind("booking");
        record.setEntity("bookings");
        record.setPayload(Map.of(
                "customerName", "Trường Giang",
                "customerEmail", "giang@example.com",
                "service", "consultation",
                "bookingDate", "2026-11-07T08:00",
                "notes", "Phòng VIP"
        ));
        record.setRuntime(Map.of());

        when(tenantIdentityApi.findUserById(creatorId)).thenReturn(
                new TenantIdentityApi.IdentitySnapshot(creatorId, orgId, "staff@example.com", "Staff Member", com.openvie.api.tenant.api.OrgRole.MEMBER, "ACTIVE", false)
        );

        scheduler.processRecord(record, now);

        // 1. In-app notification sent
        verify(notificationRuntimeApi).createInAppNotification(
                eq(tenantId),
                eq(creatorId),
                eq("BOOKING_REMINDER"),
                eq("Nhắc lịch hẹn sắp tới"),
                contains("Trường Giang"),
                eq("/apps/booking")
        );

        // 2. Customer email sent
        verify(notificationRuntimeApi).sendRawEmail(
                eq(orgId),
                eq("giang@example.com"),
                contains("Nhắc lịch hẹn"),
                contains("Trường Giang")
        );

        // 3. Creator email sent
        verify(notificationRuntimeApi).sendRawEmail(
                eq(orgId),
                eq("staff@example.com"),
                contains("Trường Giang"),
                contains("Phòng VIP")
        );

        // 4. Runtime marked with reminderSentAt and saved
        verify(recordRepository).save(record);
        assertNotNull(record.getRuntime().get("reminderSentAt"));
        assertEquals("2026-11-07T08:00", record.getRuntime().get("reminderBookingDate"));
    }

    @Test
    void processRecordIgnoresAppointmentsOutside24Hours() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 6, 10, 0);
        BizAppRecord record = new BizAppRecord();
        record.setPayload(Map.of(
                "customerName", "Trường Giang",
                "bookingDate", "2026-11-20"
        ));

        scheduler.processRecord(record, now);

        verifyNoInteractions(notificationRuntimeApi);
        verify(recordRepository, never()).save(any());
    }
}
