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

import { beforeAll, describe, expect, it } from "vitest";
import type { ApiRequest } from "@/lib/api-request";
import {
  listNotificationsApi,
  getUnreadNotificationsCountApi,
  markNotificationAsReadApi,
  markAllNotificationsAsReadApi,
} from "@/lib/notifications-api";

beforeAll(() => {
  process.env.NEXT_PUBLIC_API_BASE_URL = "http://api.test/api/v1";
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("notifications API", () => {
  it("fetches paged notifications and unread count", async () => {
    const fakePage = {
      content: [
        {
          id: "notif-1",
          type: "BOOKING_REMINDER",
          title: "Nhắc lịch hẹn",
          message: "Lịch hẹn sắp diễn ra",
          link: "/apps/booking",
          read: false,
          createdAt: "2026-11-06T10:00:00Z",
        },
      ],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    };

    const mockRequest: ApiRequest = async (url) => {
      if (String(url).includes("/unread-count")) {
        return jsonResponse({ unreadCount: 1 });
      }
      return jsonResponse(fakePage);
    };

    const page = await listNotificationsApi(mockRequest, 0, 20);
    expect(page.content).toHaveLength(1);
    expect(page.content[0]?.title).toBe("Nhắc lịch hẹn");

    const unread = await getUnreadNotificationsCountApi(mockRequest);
    expect(unread.unreadCount).toBe(1);
  });

  it("marks one and all notifications as read", async () => {
    const calledUrls: string[] = [];
    const mockRequest: ApiRequest = async (url, init) => {
      calledUrls.push(`${init?.method ?? "GET"} ${String(url)}`);
      return new Response(null, { status: 204 });
    };

    await markNotificationAsReadApi(mockRequest, "notif-1");
    expect(calledUrls).toContain("POST http://api.test/api/v1/notifications/notif-1/read");

    await markAllNotificationsAsReadApi(mockRequest);
    expect(calledUrls).toContain("POST http://api.test/api/v1/notifications/read-all");
  });
});
