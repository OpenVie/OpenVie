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

import dagre from "@dagrejs/dagre";
import type { Node, Edge } from "@xyflow/react";
import type { BizAppManifest, BizAppEntity, BizAppBlock } from "@/types";
import { syncReportViews } from "@/lib/bizapp-report";

export interface EntityNodeData extends Record<string, unknown> {
  entity: BizAppEntity;
  error?: string;
}

export interface BlockNodeData extends Record<string, unknown> {
  block: BizAppBlock;
  error?: string;
}

const NODE_WIDTH = 260;
const MIN_NODE_HEIGHT = 180;

/** Approximate rendered height so dagre does not overlap tall nodes. */
function nodeHeight(node: Node): number {
  if (node.type === "entityNode") {
    const fields = (node.data as EntityNodeData).entity?.fields?.length ?? 0;
    return Math.max(MIN_NODE_HEIGHT, 125 + fields * 22);
  }
  if (node.type === "blockNode") {
    const params = Object.keys((node.data as BlockNodeData).block?.params ?? {}).length;
    return Math.max(MIN_NODE_HEIGHT, 125 + params * 22);
  }
  return MIN_NODE_HEIGHT;
}

export function manifestToGraph(manifest: BizAppManifest): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = [];
  const edges: Edge[] = [];
  const layout = manifest.ui?.layout ?? {};

  // 1. Entity Nodes
  (manifest.entities || []).forEach((entity, index) => {
    const id = `entity-${entity.name}`;
    const pos = layout[entity.name] || { x: 50, y: index * 220 + 50 };
    nodes.push({
      id,
      type: "entityNode",
      position: { x: pos.x, y: pos.y },
      data: { entity },
    });

    // Ref field edges (entity -> entity)
    (entity.fields || []).forEach((field) => {
      if (field.type === "ref" && field.entity) {
        edges.push({
          id: `ref-${entity.name}-${field.name}->${field.entity}`,
          source: `entity-${entity.name}`,
          target: `entity-${field.entity}`,
          label: field.name,
          animated: true,
          data: { kind: "ref" },
        });
      }
    });

    // Hook edges (entity -> block); a repeated target under one hook key must
    // not produce a duplicate edge id.
    if (entity.hooks) {
      const hookIds = new Set<string>();
      Object.entries(entity.hooks).forEach(([hookKey, targets]) => {
        (targets || []).forEach((target) => {
          if (target.startsWith("webhook_out:")) return;
          const edgeId = `hook-${entity.name}-${hookKey}->${target}`;
          if (hookIds.has(edgeId)) return;
          hookIds.add(edgeId);
          edges.push({
            id: edgeId,
            source: `entity-${entity.name}`,
            target: `block-${target}`,
            label: hookKey,
            data: { kind: "hook" },
          });
        });
      });
    }
  });

  // 2. Block Nodes
  (manifest.blocks || []).forEach((block, index) => {
    const id = `block-${block.id}`;
    const pos = layout[block.id] || { x: 450, y: index * 220 + 50 };
    nodes.push({
      id,
      type: "blockNode",
      position: { x: pos.x, y: pos.y },
      data: { block },
    });

    // Report view edges (report -> entity), one edge per entity: several views
    // over the same entity would otherwise overlap on the same path and stack
    // their labels. The edge carries the view names for the inspector.
    if (block.type === "report" && Array.isArray(block.params?.views)) {
      const viewsByEntity = new Map<string, string[]>();
      block.params.views.forEach((view: { name?: string; entity?: string }, viewIndex: number) => {
        if (!view.entity) return;
        const viewName = view.name || `view-${viewIndex}`;
        viewsByEntity.set(view.entity, [...(viewsByEntity.get(view.entity) ?? []), viewName]);
      });
      for (const [entity, viewNames] of viewsByEntity) {
        edges.push({
          id: `report-${block.id}->${entity}`,
          source: `block-${block.id}`,
          target: `entity-${entity}`,
          label: viewNames.length === 1 ? viewNames[0] : `${viewNames.length} views`,
          data: { kind: "report", views: viewNames },
        });
      }
    }

    // Schedule edge (external_call -> entity): a connector polled on a timer
    // has no hook, so without this line the canvas shows it floating. It is
    // read-only: the interval and the selected records live in params.schedule.
    const schedule: unknown = block.params?.schedule;
    const scheduleEntity = schedule !== null && typeof schedule === "object"
      && "entity" in schedule && typeof schedule.entity === "string"
      ? schedule.entity
      : null;
    if (block.type === "external_call" && scheduleEntity) {
      const every = schedule !== null && typeof schedule === "object"
        && "every" in schedule && typeof schedule.every === "string"
        ? schedule.every
        : "";
      edges.push({
        id: `schedule-${block.id}->${scheduleEntity}`,
        source: `block-${block.id}`,
        target: `entity-${scheduleEntity}`,
        label: every ? `every ${every}` : "scheduled",
        data: { kind: "schedule", every },
        animated: true,
        style: { strokeDasharray: "6 4" },
      });
    }

    // Assistant block edge (assistant -> knowledge_base)
    if (block.type === "assistant" && typeof block.params?.knowledgeBaseBlock === "string") {
      edges.push({
        id: `assistant-${block.id}->${block.params.knowledgeBaseBlock}`,
        source: `block-${block.id}`,
        target: `block-${block.params.knowledgeBaseBlock}`,
        label: "kb",
        data: { kind: "assistant" },
      });
    }
  });

  return { nodes, edges };
}

export function graphToManifest(
  nodes: Node[],
  edges: Edge[],
  baseManifest: BizAppManifest
): BizAppManifest {
  const entities: BizAppEntity[] = [];
  const blocks: BizAppBlock[] = [];
  const layout: Record<string, { x: number; y: number }> = {};
  const hookTargets = new Map<string, Record<string, string[]>>();

  for (const edge of edges) {
    if (!edge.source.startsWith("entity-") || !edge.target.startsWith("block-")) {
      continue;
    }
    const entityName = edge.source.slice("entity-".length);
    const blockId = edge.target.slice("block-".length);
    const label = String(edge.label || "onCreate");
    const hookKey = ["onCreate", "onUpdate", "onDecide"].includes(label) ? label : "onCreate";
    const hooks = hookTargets.get(entityName) || {};
    hooks[hookKey] = [...(hooks[hookKey] || []), blockId];
    hookTargets.set(entityName, hooks);
  }

  nodes.forEach((node) => {
    if (node.type === "entityNode") {
      const data = node.data as EntityNodeData;
      if (data.entity) {
        const hooks = hookTargets.get(data.entity.name);
        entities.push({
          ...data.entity,
          hooks: hooks && Object.keys(hooks).length > 0 ? hooks : undefined,
        });
        layout[data.entity.name] = {
          x: Math.round(node.position.x),
          y: Math.round(node.position.y),
        };
      }
    } else if (node.type === "blockNode") {
      const data = node.data as BlockNodeData;
      if (data.block) {
        blocks.push(data.block);
        layout[data.block.id] = {
          x: Math.round(node.position.x),
          y: Math.round(node.position.y),
        };
      }
    }
  });

  return syncReportViews({
    ...baseManifest,
    entities,
    blocks,
    ui: {
      ...baseManifest.ui,
      views: baseManifest.ui?.views || [],
      layout,
    },
  });
}

export function layoutGraph(nodes: Node[], edges: Edge[]): { nodes: Node[]; edges: Edge[] } {
  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: "LR", nodesep: 50, ranksep: 100 });
  g.setDefaultEdgeLabel(() => ({}));

  nodes.forEach((node) => {
    g.setNode(node.id, { width: NODE_WIDTH, height: nodeHeight(node) });
  });

  edges.forEach((edge) => {
    g.setEdge(edge.source, edge.target);
  });

  dagre.layout(g);

  const updatedNodes = nodes.map((node) => {
    const nodeWithPos = g.node(node.id);
    if (!nodeWithPos) return node;
    return {
      ...node,
      position: {
        x: Math.round(nodeWithPos.x - NODE_WIDTH / 2),
        y: Math.round(nodeWithPos.y - nodeHeight(node) / 2),
      },
    };
  });

  return { nodes: updatedNodes, edges };
}
