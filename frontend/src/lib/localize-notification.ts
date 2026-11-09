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

import type { NotificationItem } from "@/types";

export interface LocalizedNotification {
  title: string;
  message: string;
  badge: string;
}

const TYPE_BADGES: Record<string, { vi: string; en: string }> = {
  BOOKING_REMINDER: { vi: "Nhắc lịch", en: "Reminder" },
  WORKFLOW_DECIDED: { vi: "Phê duyệt", en: "Approval" },
  USER_INVITED: { vi: "Lời mời", en: "Invitation" },
  DOCUMENT_COMPLETED: { vi: "Hoàn tất", en: "Completed" },
  DOCUMENT_FAILED: { vi: "Thất bại", en: "Failed" },
};

/**
 * Localizes notification content (both existing DB rows and new events)
 * to match the active user locale.
 */
export function localizeNotification(
  item: NotificationItem,
  locale: string,
): LocalizedNotification {
  const isVi = locale.toLowerCase().startsWith("vi");
  const badgeMap = TYPE_BADGES[item.type];
  const badge = badgeMap ? (isVi ? badgeMap.vi : badgeMap.en) : "";

  let title = item.title;
  let message = item.message;

  if (isVi) {
    // 1. Workflow decisions
    if (title === "Document approved") {
      title = "Tài liệu đã được phê duyệt";
    } else if (title === "Document rejected") {
      title = "Tài liệu bị từ chối";
    }

    if (message) {
      // e.g. "file.docx was approved at approval level 1"
      // or "file.docx was rejected at approval level 1 — reason: ..."
      const match = message.match(/^(.*?) was (approved|rejected) at approval level (\d+)(?: — reason: (.*))?$/);
      if (match) {
        const [, file, verdict, level, reason] = match;
        const verdictText = verdict === "approved" ? "đã được phê duyệt" : "bị từ chối";
        message = `${file} ${verdictText} ở cấp phê duyệt ${level}${reason ? ` — lý do: ${reason}` : ""}`;
      }
    }

    // 2. Invitations
    if (title.startsWith("You're invited to ")) {
      title = title.replace("You're invited to ", "Lời mời tham gia ");
    }
    if (message && message.startsWith("Team invitation sent to ")) {
      message = message.replace("Team invitation sent to ", "Lời mời tham gia đã được gửi tới ");
    }

    // 3. Document completion
    if (title === "Document completed") {
      title = "Tài liệu đã xử lý xong";
    } else if (title === "Document failed") {
      title = "Xử lý tài liệu thất bại";
    }
  } else {
    // English locale for Vietnamese messages
    if (title === "Nhắc lịch hẹn sắp tới") {
      title = "Upcoming appointment reminder";
    }
    if (message && message.startsWith("Lịch hẹn với ")) {
      const match = message.match(/^Lịch hẹn với (.*?) \((.*?)\) vào thời gian: (.*)$/);
      if (match) {
        const [, name, service, date] = match;
        message = `Appointment with ${name} (${service}) at: ${date}`;
      }
    }
  }

  return { title, message, badge };
}
