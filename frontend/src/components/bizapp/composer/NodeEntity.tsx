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
import { useLocale, useTranslations } from "next-intl";
import { Handle, Position, type NodeProps } from "@xyflow/react";
import { Database, AlertCircle } from "lucide-react";
import type { EntityNodeData } from "./graph-manifest";
import { useNodeValidationError } from "./validation-context";

export const NodeEntity = memo(function NodeEntity({ id, data, selected }: NodeProps) {
  const t = useTranslations("BizApps.composer");
  const locale = useLocale();
  const nodeData = data as EntityNodeData;
  const entity = nodeData.entity;
  const fields = entity?.fields || [];
  const error = useNodeValidationError(id);
  const localizedLabel = locale.startsWith("vi") ? entity?.label?.vi : entity?.label?.en;
  const displayName = localizedLabel || entity?.name || t("node.entityFallback");
  const showTechnicalName = Boolean(entity?.name && entity.name !== displayName);

  return (
    <div
      className={`rounded-xl border bg-card text-card-foreground shadow-md transition-all w-[240px] ${
        error
          ? "border-destructive ring-2 ring-destructive"
          : selected
            ? "ring-2 ring-primary border-primary"
            : "border-border"
      }`}
    >
      <Handle type="target" position={Position.Left} className="w-3 h-3 bg-primary" />

      <div className="flex items-center gap-2 border-b bg-muted/40 px-3 py-2 rounded-t-xl">
        <Database className="h-4 w-4 text-primary shrink-0" />
        <div className="min-w-0 flex-1">
          <div className="truncate font-semibold text-xs" title={displayName}>
            {displayName}
          </div>
          {showTechnicalName && (
            <div
              className="truncate font-mono text-[10px] text-muted-foreground"
              title={entity?.name}
            >
              {entity?.name}
            </div>
          )}
        </div>
        {error && (
          <span title={error} className="inline-flex shrink-0">
            <AlertCircle className="h-4 w-4 text-destructive" />
          </span>
        )}
      </div>

      <div className="p-3 space-y-1.5 text-xs">
        <div className="text-muted-foreground font-medium">
          {t("node.fieldCount", { count: fields.length })}:
        </div>
        <div className="space-y-1">
          {fields.map((f) => {
            const target = f.type === "ref" && f.entity ? ` → ${f.entity}` : "";
            return (
              <div key={f.name} className="flex items-start justify-between gap-2 text-[11px] bg-muted/30 px-1.5 py-0.5 rounded">
                <span className="min-w-0 font-mono break-all" title={f.name}>{f.name}</span>
                <span
                  className="shrink-0 max-w-[60%] truncate text-muted-foreground text-right"
                  title={`${f.type}${target}`}
                >
                  {f.type}{target}
                </span>
              </div>
            );
          })}
        </div>
      </div>

      <Handle type="source" position={Position.Right} className="w-3 h-3 bg-primary" />
    </div>
  );
});
