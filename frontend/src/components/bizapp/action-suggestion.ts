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

import type { BizAppInstalledAppView } from "@/types";

export interface BizAppActionSuggestion {
  type: "create_record";
  entityName: string;
  label: string;
  payload: Record<string, unknown>;
}

export function extractActionSuggestion(
  query: string,
  app: BizAppInstalledAppView
): BizAppActionSuggestion | null {
  const lower = query.toLowerCase();
  const entities = app.manifest.entities || [];
  if (entities.length === 0) return null;

  const isCreateIntent =
    lower.includes("tạo") ||
    lower.includes("thêm") ||
    lower.includes("đặt") ||
    lower.includes("create") ||
    lower.includes("add") ||
    lower.includes("new") ||
    lower.includes("book") ||
    lower.includes("schedule");

  if (!isCreateIntent) return null;

  let matchedEntity = entities.find(
    (e) =>
      lower.includes(e.name.toLowerCase()) ||
      (e.label?.vi && lower.includes(e.label.vi.toLowerCase())) ||
      (e.label?.en && lower.includes(e.label.en.toLowerCase()))
  );

  if (!matchedEntity && entities.length === 1) {
    matchedEntity = entities[0];
  }

  if (!matchedEntity) return null;

  const payload: Record<string, unknown> = {};

  // Extract phone
  const phoneMatch = query.match(/(?:0|\+84)[0-9]{9,10}\b/);
  if (phoneMatch && matchedEntity.fields.some((f) => f.name === "phone")) {
    payload.phone = phoneMatch[0];
  }

  // Extract email
  const emailMatch = query.match(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/);
  if (emailMatch) {
    const emailField = matchedEntity.fields.find(
      (f) => f.format === "email" || f.name.toLowerCase().includes("email")
    );
    if (emailField) {
      payload[emailField.name] = emailMatch[0];
    }
  }

  // Extract numbers / currency values
  const numberMatches = query.match(/(\d+[\d,.]*)\s*(triệu|tr|tỷ|vnd|usd|\$|khách|người)?/i);
  if (numberMatches) {
    const rawNum = numberMatches[1].replace(/,/g, "");
    let val = parseFloat(rawNum);
    const unit = (numberMatches[2] || "").toLowerCase();
    if (unit === "triệu" || unit === "tr") {
      val = val * 1_000_000;
    } else if (unit === "tỷ") {
      val = val * 1_000_000_000;
    }
    const numField = matchedEntity.fields.find((f) => f.type === "number");
    if (numField && !isNaN(val)) {
      payload[numField.name] = val;
    }
  }

  // Extract date
  const dateMatch = query.match(/\b(\d{4}-\d{2}-\d{2}|\d{1,2}\/\d{1,2}\/\d{4})\b/);
  if (dateMatch) {
    const dateField = matchedEntity.fields.find((f) => f.type === "date");
    if (dateField) {
      payload[dateField.name] = dateMatch[0];
    }
  }

  // Extract name/title
  const namePatterns = [
    /(?:tên là|tên|cho|khách hàng|lead|customer|name)\s+([^,.;\n]+?)(?:[,.;]|\s+(?:số|với|deal|sđt|email|ngày|vào)|\s*$)/i,
    /(?:tạo|thêm|đặt|create|add|book)\s+(?:lead|khách hàng|lịch hẹn|lịch|bản ghi|record)?\s*(?:cho|tên)?\s*([^,.;\n]+?)(?:[,.;]|\s+(?:với|deal|giá|ngày|email|sđt)|\s*$)/i,
  ];

  for (const pat of namePatterns) {
    const m = query.match(pat);
    if (m && m[1]) {
      const candidate = m[1].trim();
      if (candidate.length > 1 && !candidate.startsWith("0")) {
        const nameField = matchedEntity.fields.find(
          (f) => f.name === "name" || f.name === "customerName" || f.name === "title"
        );
        if (nameField && !payload[nameField.name]) {
          payload[nameField.name] = candidate;
        }
        break;
      }
    }
  }

  // Extract stage / status
  const stageField = matchedEntity.fields.find(
    (f) => f.name === "stage" || f.name === "signatureStatus" || f.name === "status"
  );
  if (stageField && stageField.options) {
    for (const opt of stageField.options) {
      if (lower.includes(opt.toLowerCase())) {
        payload[stageField.name] = opt;
        break;
      }
    }
    if (!payload[stageField.name] && stageField.options.includes("NEW")) {
      payload[stageField.name] = "NEW";
    }
  }

  const label = matchedEntity.label?.vi || matchedEntity.name;
  return {
    type: "create_record",
    entityName: matchedEntity.name,
    label: `Tạo ${label}`,
    payload,
  };
}
