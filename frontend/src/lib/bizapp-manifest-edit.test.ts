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
import { removeBlockFromManifest, removeEntityFromManifest } from "./bizapp-manifest-edit";

function manifest(): BizAppManifest {
  return {
    schema: "bizapp.v1",
    kind: "purchase-approval",
    name: { vi: "Duyệt mua sắm", en: "Purchase approval" },
    version: 1,
    rbac: {},
    entities: [
      {
        name: "requests",
        label: { vi: "Yêu cầu", en: "Requests" },
        fields: [{ name: "title", type: "string" }],
        hooks: { onCreate: ["approval1", "notify1"] },
      },
      {
        name: "vendors",
        label: { vi: "Nhà cung cấp", en: "Vendors" },
        fields: [{ name: "name", type: "string" }],
      },
    ],
    blocks: [
      {
        id: "approval1",
        type: "approval_workitem",
        params: { bpmnTemplate: "processes/bizapp-single-approval.bpmn20.xml" },
      },
      {
        id: "notify1",
        type: "notify",
        params: { to: ["creator"] },
      },
      {
        id: "report1",
        type: "report",
        params: {
          views: [
            { name: "requests_by_status", entity: "requests" },
            { name: "vendors", entity: "vendors" },
          ],
        },
      },
      { id: "assistant1", type: "assistant", params: {} },
    ],
    ui: {
      views: [
        "registry:requests",
        "form:requests",
        "timeline:requests",
        "registry:vendors",
        "report:requests_by_status",
        "report:vendors",
        "assistant:assistant1",
      ],
      layout: {
        requests: { x: 0, y: 0 },
        vendors: { x: 0, y: 1 },
        approval1: { x: 1, y: 0 },
      },
    },
  };
}

describe("bizapp manifest deletion", () => {
  it("removes entity views, report views and layout without touching other entities", () => {
    const next = removeEntityFromManifest(manifest(), "requests");

    expect(next.entities?.map((entity) => entity.name)).toEqual(["vendors"]);
    expect(next.ui?.views).toEqual([
      "registry:vendors",
      "report:vendors",
      "assistant:assistant1",
    ]);
    expect(next.ui?.layout).not.toHaveProperty("requests");
    expect(next.blocks?.find((block) => block.id === "report1")?.params?.views).toEqual([
      { name: "vendors", entity: "vendors" },
    ]);
  });

  it("removes a block, its hook targets, assistant tab and layout", () => {
    const next = removeBlockFromManifest(manifest(), "approval1");

    expect(next.blocks?.map((block) => block.id)).not.toContain("approval1");
    expect(next.entities?.[0].hooks).toEqual({ onCreate: ["notify1"] });
    expect(next.ui?.layout).not.toHaveProperty("approval1");
    expect(next.ui?.views).toContain("assistant:assistant1");
  });

  it("drops the report block when its last view is removed with an entity", () => {
    const reportOnly = manifest();
    reportOnly.blocks = reportOnly.blocks?.filter((block) => block.id !== "report1");
    reportOnly.blocks?.push({
      id: "report1",
      type: "report",
      params: { views: [{ name: "vendors", entity: "vendors" }] },
    });

    const next = removeEntityFromManifest(reportOnly, "vendors");

    expect(next.blocks?.some((block) => block.id === "report1")).toBe(false);
    expect(next.ui?.views).not.toContain("report:vendors");
  });
});
