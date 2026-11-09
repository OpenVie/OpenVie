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
import type { BizAppManifest } from "@/types";
import {
  defaultReportView,
  slugifyReportName,
  syncReportViews,
  uniqueReportName,
} from "@/lib/bizapp-report";

const manifest = (views: string[] = []): BizAppManifest => ({
  schema: "bizapp.v1",
  kind: "demo",
  name: { en: "Demo" },
  entities: [{ name: "requests", label: { en: "Requests" }, fields: [] }],
  blocks: [],
  ui: { views },
});

describe("report builder helpers", () => {
  it("creates a valid, readable default view from the first entity", () => {
    const view = defaultReportView(manifest().entities, []);

    expect(view).toMatchObject({
      name: "requests_overview",
      title: "Requests overview",
      entity: "requests",
      limit: 100,
    });
  });

  it("slugifies titles and avoids duplicate names", () => {
    expect(slugifyReportName("Đơn mua sắm theo tháng")).toBe("don_mua_sam_theo_thang");
    expect(uniqueReportName("Requests overview", [{ name: "requests_overview", entity: "requests" }]))
      .toBe("requests_overview_2");
  });

  it("adds every report tab and removes stale ones", () => {
    const synced = syncReportViews({
      ...manifest(["registry:requests", "report:old"]),
      blocks: [
        {
          id: "report1",
          type: "report",
          params: {
            views: [{ name: "requests_overview", title: "Requests overview", entity: "requests" }],
          },
        },
      ],
    });

    expect(synced.ui?.views).toEqual(["registry:requests", "report:requests_overview"]);
  });
});

describe("new block defaults", () => {
  it("creates a valid report block and a valid webhook block", async () => {
    const { defaultBlockParams } = await import("@/lib/bizapp-block");
    const entities = manifest().entities;
    expect(defaultBlockParams("report", entities)).toEqual({
      views: [expect.objectContaining({ name: "requests_overview", entity: "requests" })],
    });
    expect(defaultBlockParams("webhook_out", entities)).toEqual({
      events: ["record.created", "record.updated"],
    });
    expect(defaultBlockParams("notify", entities)).toEqual({ to: ["creator"] });
  });
});
