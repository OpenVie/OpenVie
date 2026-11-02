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

export interface EntityNodeData extends Record<string, unknown> {
  entity: BizAppEntity;
  error?: string;
}

export interface BlockNodeData extends Record<string, unknown> {
  block: BizAppBlock;
  error?: string;
}

const NODE_WIDTH = 260;
const NODE_HEIGHT = 180;

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
        });
      }
    });

    // Hook edges (entity -> block)
    if (entity.hooks) {
      Object.entries(entity.hooks).forEach(([hookKey, targets]) => {
        (targets || []).forEach((target) => {
          if (!target.startsWith("webhook_out:")) {
            edges.push({
              id: `hook-${entity.name}-${hookKey}->${target}`,
              source: `entity-${entity.name}`,
              target: `block-${target}`,
              label: hookKey,
            });
          }
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

    // Report view edges (report -> entity)
    if (block.type === "report" && Array.isArray(block.params?.views)) {
      block.params.views.forEach((view: { name?: string; entity?: string }) => {
        if (view.entity) {
          edges.push({
            id: `report-${block.id}->${view.entity}`,
            source: `block-${block.id}`,
            target: `entity-${view.entity}`,
            label: view.name || "view",
          });
        }
      });
    }

    // Assistant block edge (assistant -> knowledge_base)
    if (block.type === "assistant" && typeof block.params?.knowledgeBaseBlock === "string") {
      edges.push({
        id: `assistant-${block.id}->${block.params.knowledgeBaseBlock}`,
        source: `block-${block.id}`,
        target: `block-${block.params.knowledgeBaseBlock}`,
        label: "kb",
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

  nodes.forEach((node) => {
    if (node.type === "entityNode") {
      const data = node.data as EntityNodeData;
      if (data.entity) {
        entities.push(data.entity);
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

  return {
    ...baseManifest,
    entities,
    blocks,
    ui: {
      ...baseManifest.ui,
      views: baseManifest.ui?.views || [],
      layout,
    },
  };
}

export function layoutGraph(nodes: Node[], edges: Edge[]): { nodes: Node[]; edges: Edge[] } {
  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: "LR", nodesep: 50, ranksep: 100 });
  g.setDefaultEdgeLabel(() => ({}));

  nodes.forEach((node) => {
    g.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
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
        y: Math.round(nodeWithPos.y - NODE_HEIGHT / 2),
      },
    };
  });

  return { nodes: updatedNodes, edges };
}
