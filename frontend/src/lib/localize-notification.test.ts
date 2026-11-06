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

import { describe, expect, it } from "vitest";
import { localizeNotification } from "@/lib/localize-notification";
import type { NotificationItem } from "@/types";

describe("localizeNotification", () => {
  it("translates workflow approval to Vietnamese", () => {
    const item: NotificationItem = {
      id: "1",
      type: "WORKFLOW_DECIDED",
      title: "Document approved",
      message: "report.docx was approved at approval level 1",
      read: false,
      createdAt: "2026-11-06T10:00:00Z",
    };

    const vi = localizeNotification(item, "vi");
    expect(vi.title).toBe("Tài liệu đã được phê duyệt");
    expect(vi.message).toBe("report.docx đã được phê duyệt ở cấp phê duyệt 1");
    expect(vi.badge).toBe("Phê duyệt");
  });

  it("translates workflow rejection with reason to Vietnamese", () => {
    const item: NotificationItem = {
      id: "2",
      type: "WORKFLOW_DECIDED",
      title: "Document rejected",
      message: "contract.pdf was rejected at approval level 2 — reason: Thiếu chữ ký",
      read: false,
      createdAt: "2026-11-06T10:00:00Z",
    };

    const vi = localizeNotification(item, "vi");
    expect(vi.title).toBe("Tài liệu bị từ chối");
    expect(vi.message).toBe("contract.pdf bị từ chối ở cấp phê duyệt 2 — lý do: Thiếu chữ ký");
  });

  it("translates invitations to Vietnamese", () => {
    const item: NotificationItem = {
      id: "3",
      type: "USER_INVITED",
      title: "You're invited to Marketing",
      message: "Team invitation sent to user@example.com",
      read: false,
      createdAt: "2026-11-06T10:00:00Z",
    };

    const vi = localizeNotification(item, "vi");
    expect(vi.title).toBe("Lời mời tham gia Marketing");
    expect(vi.message).toBe("Lời mời tham gia đã được gửi tới user@example.com");
    expect(vi.badge).toBe("Lời mời");
  });

  it("handles booking reminder badge and English fallback", () => {
    const item: NotificationItem = {
      id: "4",
      type: "BOOKING_REMINDER",
      title: "Nhắc lịch hẹn sắp tới",
      message: "Lịch hẹn với Binz (tour) vào thời gian: 2026-11-07 14:00",
      read: false,
      createdAt: "2026-11-06T10:00:00Z",
    };

    const vi = localizeNotification(item, "vi");
    expect(vi.badge).toBe("Nhắc lịch");
    expect(vi.title).toBe("Nhắc lịch hẹn sắp tới");

    const en = localizeNotification(item, "en");
    expect(en.badge).toBe("Reminder");
    expect(en.title).toBe("Upcoming appointment reminder");
    expect(en.message).toBe("Appointment with Binz (tour) at: 2026-11-07 14:00");
  });
});
