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

  it("maps edge add/remove back to entity hooks", () => {
    const { nodes, edges } = manifestToGraph(SAMPLE_MANIFEST);
    const withoutHook = graphToManifest(
      nodes,
      edges.filter((edge) => !(edge.source === "entity-leads" && edge.target === "block-notifyOwner")),
      SAMPLE_MANIFEST,
    );
    expect(withoutHook.entities[0].hooks).toBeUndefined();

    const withNewHook = graphToManifest(nodes, [
      ...edges.filter((edge) => !(edge.source === "entity-leads" && edge.target === "block-notifyOwner")),
      {
        id: "hook-new",
        source: "entity-leads",
        target: "block-notifyOwner",
        label: "onDecide",
      },
    ], SAMPLE_MANIFEST);
    expect(withNewHook.entities[0].hooks).toEqual({ onDecide: ["notifyOwner"] });
  });

  it("tags edge kinds so the inspector can tell hooks from derived links", () => {
    const { nodes, edges } = manifestToGraph(SAMPLE_MANIFEST);
    const hookEdge = edges.find((e) => e.source === "entity-leads" && e.target === "block-notifyOwner");
    const reportEdge = edges.find((e) => e.source === "block-leadPipeline" && e.target === "entity-leads");

    expect(hookEdge?.data).toMatchObject({ kind: "hook" });
    expect(reportEdge?.data).toMatchObject({ kind: "report", views: ["pipeline"] });

    // Relabelling a derived report edge must never invent a hook.
    const relabelled = graphToManifest(nodes, [...edges, { ...reportEdge!, label: "onUpdate" }], SAMPLE_MANIFEST);
    expect(relabelled.entities[0].hooks).toEqual({ onCreate: ["notifyOwner"] });
  });

  it("collapses report views over one entity into a single labelled edge", () => {
    const manifest: BizAppManifest = {
      ...SAMPLE_MANIFEST,
      blocks: [
        ...(SAMPLE_MANIFEST.blocks ?? []),
        {
          id: "leadReports",
          type: "report",
          params: {
            views: [
              { name: "recent_readings", entity: "leads" },
              { name: "by_device", entity: "leads" },
            ],
          },
        },
      ],
    };

    const { edges } = manifestToGraph(manifest);
    const ids = edges.map((edge) => edge.id);
    const reportEdges = edges.filter((edge) => edge.id === "report-leadReports->leads");

    expect(new Set(ids).size).toBe(ids.length);
    expect(reportEdges).toHaveLength(1);
    expect(reportEdges[0].label).toBe("2 views");
    expect(reportEdges[0].data).toMatchObject({ kind: "report", views: ["recent_readings", "by_device"] });
  });

  it("dedupes a repeated hook target instead of duplicating the edge id", () => {
    const manifest: BizAppManifest = {
      ...SAMPLE_MANIFEST,
      entities: [{ ...SAMPLE_MANIFEST.entities[0], hooks: { onCreate: ["notifyOwner", "notifyOwner"] } }],
    };

    const { edges } = manifestToGraph(manifest);
    const hookEdges = edges.filter((edge) => edge.source === "entity-leads" && edge.target === "block-notifyOwner");

    expect(hookEdges).toHaveLength(1);
  });

  it("draws a read-only schedule edge for timer-driven connectors", () => {
    const manifest: BizAppManifest = {
      ...SAMPLE_MANIFEST,
      blocks: [
        ...(SAMPLE_MANIFEST.blocks ?? []),
        {
          id: "pollSensor",
          type: "external_call",
          params: {
            protocol: "mqtt",
            brokerUrl: "tcp://localhost:1883",
            topic: "openvie/aq/aq-01/telemetry",
            schedule: { every: "PT30S", entity: "leads", select: { enabled: true }, limit: 10 },
          },
        },
      ],
    };

    const { nodes, edges } = manifestToGraph(manifest);
    const scheduleEdge = edges.find((edge) => edge.id === "schedule-pollSensor->leads");

    expect(scheduleEdge?.label).toBe("every PT30S");
    expect(scheduleEdge?.data).toMatchObject({ kind: "schedule", every: "PT30S" });
    expect(scheduleEdge?.style).toMatchObject({ strokeDasharray: "6 4" });

    const ids = edges.map((edge) => edge.id);
    expect(new Set(ids).size).toBe(ids.length);

    // Derived like report edges: rebuilding the manifest must not add a hook.
    expect(graphToManifest(nodes, edges, manifest).entities[0].hooks).toEqual({ onCreate: ["notifyOwner"] });
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
