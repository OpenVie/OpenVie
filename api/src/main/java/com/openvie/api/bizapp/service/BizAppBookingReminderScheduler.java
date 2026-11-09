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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sweeps active booking records every minute and sends reminders within 24 hours
 * of the scheduled appointment:
 * 1. In-app notification for the creator/staff on UI (bell).
 * 2. Email notification for the customer (customerEmail) and the creator.
 */
@Service
@Slf4j(topic = "BIZAPP-REMINDER")
@RequiredArgsConstructor
public class BizAppBookingReminderScheduler {

    private final BizAppRecordRepository recordRepository;
    private final NotificationRuntimeApi notificationRuntimeApi;
    private final TenantIdentityApi tenantIdentityApi;
    private final BizAppProperties properties;

    @Value("${app.bizapp.reminders.enabled:true}")
    private boolean remindersEnabled = true;

    @Scheduled(fixedDelayString = "${app.bizapp.reminders.scan-delay-ms:60000}")
    @Transactional
    public void scanAndRemind() {
        if (!properties.enabled() || !remindersEnabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<BizAppRecord> candidates = recordRepository.findDueBookingRemindersBatch(50);
        for (BizAppRecord record : candidates) {
            try {
                processRecord(record, now);
            } catch (Exception ex) {
                log.warn("Failed to process booking reminder for record {}: {}", record.getId(), ex.getMessage());
            }
        }
    }

    void processRecord(BizAppRecord record, LocalDateTime now) {
        Map<String, Object> payload = record.getPayload();
        if (payload == null || payload.isEmpty()) return;

        Object rawDateObj = payload.get("bookingDate");
        if (rawDateObj == null) return;
        String rawDate = String.valueOf(rawDateObj).trim();
        if (rawDate.isEmpty()) return;

        if (!isWithin24Hours(rawDate, now)) {
            return;
        }

        String customerName = stringVal(payload.get("customerName"), "Khách hàng");
        String customerEmail = stringVal(payload.get("customerEmail"), "");
        String service = stringVal(payload.get("service"), "Tư vấn");
        String notes = stringVal(payload.get("notes"), "");

        // 1. In-app notification for the creator/staff
        String inAppTitle = "Nhắc lịch hẹn sắp tới";
        String inAppMessage = String.format("Lịch hẹn với %s (%s) vào thời gian: %s", customerName, service, rawDate);
        notificationRuntimeApi.createInAppNotification(
                record.getTenantId(),
                record.getCreatedBy(),
                "BOOKING_REMINDER",
                inAppTitle,
                inAppMessage,
                "/apps/booking"
        );

        // 2. Email to customer if email is valid
        if (customerEmail.contains("@")) {
            String subject = "OpenVie Booking — Nhắc lịch hẹn sắp tới (" + service + ")";
            String html = buildCustomerReminderHtml(customerName, service, rawDate, notes);
            notificationRuntimeApi.sendRawEmail(record.getOrgId(), customerEmail, subject, html);
        }

        // 3. Email to creator / staff
        TenantIdentityApi.IdentitySnapshot creatorIdentity = tenantIdentityApi.findUserById(record.getCreatedBy());
        if (creatorIdentity != null && creatorIdentity.email() != null && creatorIdentity.email().contains("@")) {
            String subject = "OpenVie Booking — Nhắc lịch hẹn với " + customerName + " (" + rawDate + ")";
            String html = buildCreatorReminderHtml(customerName, customerEmail, service, rawDate, notes);
            notificationRuntimeApi.sendRawEmail(record.getOrgId(), creatorIdentity.email(), subject, html);
        }

        // 4. Mark reminded in runtime so it never repeats
        Map<String, Object> runtime = new LinkedHashMap<>(record.getRuntime() == null ? Map.of() : record.getRuntime());
        runtime.put("reminderSentAt", now.toString());
        runtime.put("reminderBookingDate", rawDate);
        record.setRuntime(runtime);
        recordRepository.save(record);
        log.info("Booking reminder processed for record {} customer={} date={}", record.getId(), customerName, rawDate);
    }

    public static boolean isWithin24Hours(String rawDate, LocalDateTime now) {
        if (rawDate == null || rawDate.isBlank()) return false;
        try {
            int tIndex = rawDate.indexOf('T');
            if (tIndex > 0) {
                String sub = rawDate.length() > 16 ? rawDate.substring(0, 16) : rawDate;
                LocalDateTime target = LocalDateTime.parse(sub);
                return !now.isAfter(target) && !now.isBefore(target.minusHours(24));
            } else {
                LocalDate targetDate = LocalDate.parse(rawDate.trim());
                LocalDate today = now.toLocalDate();
                return !today.isAfter(targetDate) && !today.isBefore(targetDate.minusDays(1));
            }
        } catch (Exception ex) {
            return false;
        }
    }

    private static String stringVal(Object obj, String fallback) {
        return obj == null ? fallback : String.valueOf(obj).trim();
    }

    private static String escapeHtml(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String buildCustomerReminderHtml(String name, String service, String date, String notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Nhắc lịch hẹn sắp tới</h1>");
        sb.append("<p>Xin chào <strong>").append(escapeHtml(name)).append("</strong>,</p>");
        sb.append("<p>Bạn có lịch hẹn sắp diễn ra trong vòng 24 giờ tới:</p>");
        sb.append("<div style=\"margin:20px 0;background:rgba(255,255,255,0.03);border:1px solid rgba(255,255,255,0.08);border-radius:10px;padding:14px 18px;\">");
        sb.append("<table style=\"width:100%;border-collapse:collapse;\">");
        sb.append("<tr><td style=\"padding:7px 0;color:#8e9992;font-size:13px;width:38%;\">Dịch vụ</td><td style=\"padding:7px 0;color:#f2f5f0;font-size:13px;font-weight:600;\">").append(escapeHtml(service)).append("</td></tr>");
        sb.append("<tr><td style=\"padding:7px 0;color:#8e9992;font-size:13px;\">Thời gian hẹn</td><td style=\"padding:7px 0;color:#f2f5f0;font-size:13px;font-weight:600;\">").append(escapeHtml(date)).append("</td></tr>");
        if (!notes.isBlank()) {
            sb.append("<tr><td style=\"padding:7px 0;color:#8e9992;font-size:13px;\">Ghi chú</td><td style=\"padding:7px 0;color:#f2f5f0;font-size:13px;font-weight:600;\">").append(escapeHtml(notes)).append("</td></tr>");
        }
        sb.append("</table></div>");
        sb.append("<p>Vui lòng có mặt đúng giờ hoặc liên hệ lại nếu có thay đổi.</p>");
        return sb.toString();
    }

    private static String buildCreatorReminderHtml(String customerName, String customerEmail, String service, String date, String notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Nhắc lịch hẹn sắp tới</h1>");
        sb.append("<p>Lịch hẹn sau đây sắp diễn ra trong vòng 24 giờ tới:</p>");
        sb.append("<div style=\"margin:20px 0;background:rgba(255,255,255,0.03);border:1px solid rgba(255,255,255,0.08);border-radius:10px;padding:14px 18px;\">");
        sb.append("<table style=\"width:100%;border-collapse:collapse;\">");
        sb.append("<tr><td style=\"padding:7px 0;color:#8e9992;font-size:13px;width:38%;\">Khách hàng</td><td style=\"padding:7px 0;color:#f2f5f0;font-size:13px;font-weight:600;\">").append(escapeHtml(customerName)).append(" (").append(escapeHtml(customerEmail)).append(")</td></tr>");
        sb.append("<tr><td style=\"padding:7px 0;color:#8e9992;font-size:13px;\">Dịch vụ</td><td style=\"padding:7px 0;color:#f2f5f0;font-size:13px;font-weight:600;\">").append(escapeHtml(service)).append("</td></tr>");
        sb.append("<tr><td style=\"padding:7px 0;color:#8e9992;font-size:13px;\">Thời gian hẹn</td><td style=\"padding:7px 0;color:#f2f5f0;font-size:13px;font-weight:600;\">").append(escapeHtml(date)).append("</td></tr>");
        if (!notes.isBlank()) {
            sb.append("<tr><td style=\"padding:7px 0;color:#8e9992;font-size:13px;\">Ghi chú</td><td style=\"padding:7px 0;color:#f2f5f0;font-size:13px;font-weight:600;\">").append(escapeHtml(notes)).append("</td></tr>");
        }
        sb.append("</table></div>");
        return sb.toString();
    }
}
