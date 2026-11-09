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

import { describe, it, expect } from "vitest";
import type { BizAppInstalledAppView } from "@/types";
import { extractActionSuggestion } from "./action-suggestion";

const MOCK_CRM_APP: BizAppInstalledAppView = {
  kind: "crm",
  version: 1,
  nameEn: "CRM Lite",
  nameVi: "CRM Lite",
  status: "ACTIVE",
  installedAt: new Date().toISOString(),
  manifest: {
    schema: "bizapp.v1",
    kind: "crm",
    name: { en: "CRM Lite", vi: "CRM Lite" },
    entities: [
      {
        name: "leads",
        label: { en: "Leads", vi: "Khách hàng" },
        fields: [
          { name: "name", type: "string", required: true },
          { name: "email", type: "string", format: "email" },
          { name: "phone", type: "string" },
          { name: "dealValue", type: "number" },
          { name: "stage", type: "enum", options: ["NEW", "WON"] },
        ],
      },
    ],
  },
};

const MOCK_BOOKING_APP: BizAppInstalledAppView = {
  kind: "booking",
  version: 1,
  nameEn: "Booking",
  nameVi: "Đặt lịch",
  status: "ACTIVE",
  installedAt: new Date().toISOString(),
  manifest: {
    schema: "bizapp.v1",
    kind: "booking",
    name: { en: "Booking", vi: "Dat lich" },
    entities: [
      {
        name: "bookings",
        label: { en: "Bookings", vi: "Lịch hẹn" },
        fields: [
          { name: "customerName", type: "string", required: true },
          { name: "customerEmail", type: "string", format: "email" },
          { name: "bookingDate", type: "date" },
          { name: "guests", type: "number" },
        ],
      },
    ],
  },
};

describe("Human-in-the-Loop Action Intent Extraction", () => {
  it("extracts lead creation parameters from natural language request", () => {
    const query = "Tạo lead Công ty Bảo với deal 100 triệu, sđt 0901234567, email contact@bao.test";
    const suggestion = extractActionSuggestion(query, MOCK_CRM_APP);

    expect(suggestion).not.toBeNull();
    expect(suggestion?.type).toBe("create_record");
    expect(suggestion?.entityName).toBe("leads");
    expect(suggestion?.payload.name).toBe("Công ty Bảo");
    expect(suggestion?.payload.dealValue).toBe(100_000_000);
    expect(suggestion?.payload.phone).toBe("0901234567");
    expect(suggestion?.payload.email).toBe("contact@bao.test");
  });

  it("extracts booking creation parameters with date", () => {
    const query = "Đặt lịch hẹn cho Anh Nam, email nam@example.com vào ngày 2026-12-01";
    const suggestion = extractActionSuggestion(query, MOCK_BOOKING_APP);

    expect(suggestion).not.toBeNull();
    expect(suggestion?.type).toBe("create_record");
    expect(suggestion?.entityName).toBe("bookings");
    expect(suggestion?.payload.customerName).toBe("Anh Nam");
    expect(suggestion?.payload.customerEmail).toBe("nam@example.com");
    expect(suggestion?.payload.bookingDate).toBe("2026-12-01");
  });

  it("returns null on informational question without create intent", () => {
    const query = "Khách hàng Bảo Motorcycles đang ở giai đoạn nào?";
    const suggestion = extractActionSuggestion(query, MOCK_CRM_APP);

    expect(suggestion).toBeNull();
  });
});
