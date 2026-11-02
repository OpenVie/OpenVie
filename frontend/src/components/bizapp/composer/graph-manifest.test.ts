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
import type { BizAppManifest } from "@/types";
import { manifestToGraph, graphToManifest, layoutGraph } from "./graph-manifest";

const SAMPLE_MANIFEST: BizAppManifest = {
  schema: "bizapp.v1",
  kind: "crm",
  name: { en: "CRM Lite", vi: "CRM Lite" },
  version: 1,
  icon: "briefcase",
  storage: "jsonb",
  rbac: {
    read: ["WORKSPACE_ADMIN", "MEMBER"],
    write: ["WORKSPACE_ADMIN", "MEMBER"],
  },
  entities: [
    {
      name: "leads",
      label: { en: "Leads", vi: "Khach hang" },
      fields: [
        { name: "name", type: "string", required: true },
        { name: "stage", type: "enum", options: ["NEW", "WON"] },
      ],
      hooks: {
        onCreate: ["notifyOwner"],
      },
    },
  ],
  blocks: [
    {
      id: "notifyOwner",
      type: "notify",
      params: { to: ["creator"], title: "New lead" },
    },
    {
      id: "leadPipeline",
      type: "report",
      params: {
        views: [
          {
            name: "pipeline",
            entity: "leads",
            groupBy: ["stage"],
            metrics: [{ fn: "count", as: "leads" }],
          },
        ],
      },
    },
  ],
  ui: {
    views: ["registry:leads", "form:leads", "report:pipeline"],
    layout: {
      leads: { x: 100, y: 150 },
      notifyOwner: { x: 400, y: 100 },
      leadPipeline: { x: 400, y: 250 },
    },
  },
};

describe("Composer graph-manifest conversions (Workstream B)", () => {
  it("converts manifest to graph nodes and edges", () => {
    const { nodes, edges } = manifestToGraph(SAMPLE_MANIFEST);

    expect(nodes).toHaveLength(3); // 1 entity + 2 blocks
    const entityNode = nodes.find((n) => n.id === "entity-leads");
    expect(entityNode).toBeDefined();
    expect(entityNode?.position).toEqual({ x: 100, y: 150 });

    // Edges should include hook (entity-leads -> block-notifyOwner) and report (block-leadPipeline -> entity-leads)
    expect(edges.length).toBeGreaterThanOrEqual(2);
    expect(edges.some((e) => e.source === "entity-leads" && e.target === "block-notifyOwner")).toBe(true);
    expect(edges.some((e) => e.source === "block-leadPipeline" && e.target === "entity-leads")).toBe(true);
  });

  it("round-trips manifest -> graph -> manifest preserving all data", () => {
    const { nodes, edges } = manifestToGraph(SAMPLE_MANIFEST);
    const roundTripped = graphToManifest(nodes, edges, SAMPLE_MANIFEST);

    expect(roundTripped.kind).toBe(SAMPLE_MANIFEST.kind);
    expect(roundTripped.entities).toEqual(SAMPLE_MANIFEST.entities);
    expect(roundTripped.blocks).toEqual(SAMPLE_MANIFEST.blocks);
    expect(roundTripped.ui?.layout?.leads).toEqual({ x: 100, y: 150 });
  });

  it("applies auto-layout correctly with dagre", () => {
    const { nodes, edges } = manifestToGraph(SAMPLE_MANIFEST);
    const layouted = layoutGraph(nodes, edges);

    expect(layouted.nodes).toHaveLength(nodes.length);
    expect(layouted.edges).toHaveLength(edges.length);
    for (const node of layouted.nodes) {
      expect(typeof node.position.x).toBe("number");
      expect(typeof node.position.y).toBe("number");
    }
  });
});
