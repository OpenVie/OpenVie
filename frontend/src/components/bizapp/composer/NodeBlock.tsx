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
import {
  AlertCircle,
  BarChart3,
  Bell,
  BookOpen,
  Bot,
  CheckCircle2,
  Globe,
  Send,
  Sliders,
} from "lucide-react";
import type { BlockNodeData } from "./graph-manifest";

function blockIcon(type: string) {
  switch (type) {
    case "approval_workitem":
      return <CheckCircle2 className="h-4 w-4 text-emerald-500" />;
    case "assistant":
      return <Bot className="h-4 w-4 text-indigo-500" />;
    case "knowledge_base":
      return <BookOpen className="h-4 w-4 text-amber-500" />;
    case "report":
      return <BarChart3 className="h-4 w-4 text-blue-500" />;
    case "notify":
      return <Bell className="h-4 w-4 text-purple-500" />;
    case "webhook_out":
      return <Send className="h-4 w-4 text-sky-500" />;
    case "external_call":
      return <Globe className="h-4 w-4 text-rose-500" />;
    default:
      return <Sliders className="h-4 w-4 text-muted-foreground" />;
  }
}

export const NodeBlock = memo(function NodeBlock({ data, selected }: NodeProps) {
  const nodeData = data as BlockNodeData;
  const block = nodeData.block;
  const params = block?.params || {};

  return (
    <div
      className={`rounded-xl border bg-card text-card-foreground shadow-md transition-all w-[240px] ${
        selected ? "ring-2 ring-primary border-primary" : "border-border"
      } ${nodeData.error ? "border-destructive ring-1 ring-destructive" : ""}`}
    >
      <Handle type="target" position={Position.Left} className="w-3 h-3 bg-primary" />

      <div className="flex items-center gap-2 border-b bg-muted/40 px-3 py-2 rounded-t-xl">
        {blockIcon(block?.type || "")}
        <div className="flex-1 truncate font-semibold text-xs">
          {block?.id || "block"}
        </div>
        <span className="text-[10px] text-muted-foreground uppercase font-mono px-1 py-0.5 bg-muted rounded">
          {block?.type}
        </span>
        {nodeData.error && (
          <span title={nodeData.error} className="inline-flex shrink-0">
            <AlertCircle className="h-4 w-4 text-destructive" />
          </span>
        )}
      </div>

      <div className="p-3 space-y-1 text-xs">
        {Object.entries(params)
          .slice(0, 3)
          .map(([k, v]) => (
            <div key={k} className="flex justify-between items-center text-[11px] truncate">
              <span className="text-muted-foreground font-mono">{k}:</span>
              <span className="font-mono truncate max-w-[120px] text-right">
                {typeof v === "object" ? JSON.stringify(v) : String(v)}
              </span>
            </div>
          ))}
        {Object.keys(params).length === 0 && (
          <div className="text-[11px] text-muted-foreground italic">No parameters</div>
        )}
      </div>

      <Handle type="source" position={Position.Right} className="w-3 h-3 bg-primary" />
    </div>
  );
});
