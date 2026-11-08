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

"use client";

import { memo } from "react";
import { Handle, Position, type NodeProps } from "@xyflow/react";
import { Database, AlertCircle } from "lucide-react";
import type { EntityNodeData } from "./graph-manifest";

export const NodeEntity = memo(function NodeEntity({ data, selected }: NodeProps) {
  const nodeData = data as EntityNodeData;
  const entity = nodeData.entity;
  const fields = entity?.fields || [];

  return (
    <div
      className={`rounded-xl border bg-card text-card-foreground shadow-md transition-all w-[240px] ${
        selected ? "ring-2 ring-primary border-primary" : "border-border"
      } ${nodeData.error ? "border-destructive ring-1 ring-destructive" : ""}`}
    >
      <Handle type="target" position={Position.Left} className="w-3 h-3 bg-primary" />

      <div className="flex items-center gap-2 border-b bg-muted/40 px-3 py-2 rounded-t-xl">
        <Database className="h-4 w-4 text-primary shrink-0" />
        <div className="flex-1 truncate font-semibold text-xs">
          {entity?.name || "entity"}
        </div>
        {nodeData.error && (
          <span title={nodeData.error} className="inline-flex shrink-0">
            <AlertCircle className="h-4 w-4 text-destructive" />
          </span>
        )}
      </div>

      <div className="p-3 space-y-1.5 text-xs">
        <div className="text-muted-foreground font-medium">
          {fields.length} {fields.length === 1 ? "field" : "fields"}:
        </div>
        <div className="space-y-1">
          {fields.map((f) => (
            <div key={f.name} className="flex items-start justify-between gap-2 text-[11px] bg-muted/30 px-1.5 py-0.5 rounded">
              <span className="min-w-0 font-mono break-all">{f.name}</span>
              <span className="shrink-0 text-muted-foreground">{f.type}</span>
            </div>
          ))}
        </div>
      </div>

      <Handle type="source" position={Position.Right} className="w-3 h-3 bg-primary" />
    </div>
  );
});
