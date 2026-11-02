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

import type { BizAppManifest, BizAppEntity, BizAppBlock } from "@/types";
import { Database, Sliders, Trash2, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";

interface ListViewProps {
  manifest: BizAppManifest;
  onSelectEntity: (entity: BizAppEntity) => void;
  onSelectBlock: (block: BizAppBlock) => void;
  onDeleteEntity: (entityName: string) => void;
  onDeleteBlock: (blockId: string) => void;
  onAddEntity: () => void;
}

export function ListView({
  manifest,
  onSelectEntity,
  onSelectBlock,
  onDeleteEntity,
  onDeleteBlock,
  onAddEntity,
}: ListViewProps) {
  const entities = manifest.entities || [];
  const blocks = manifest.blocks || [];

  return (
    <div className="flex-1 overflow-y-auto p-6 space-y-8 bg-background max-w-4xl mx-auto w-full">
      {/* Entities Section */}
      <section aria-labelledby="entities-heading" className="space-y-4">
        <div className="flex items-center justify-between border-b pb-2">
          <div className="flex items-center gap-2">
            <Database className="h-5 w-5 text-primary" />
            <h2 id="entities-heading" className="text-base font-semibold">
              Entities ({entities.length})
            </h2>
          </div>
          <Button size="sm" onClick={onAddEntity} className="gap-1.5 text-xs h-8">
            <Plus className="h-3.5 w-3.5" />
            <span>Add Entity</span>
          </Button>
        </div>

        {entities.length === 0 ? (
          <p className="text-xs text-muted-foreground italic">No entities defined yet.</p>
        ) : (
          <div className="grid gap-3">
            {entities.map((entity) => (
              <div
                key={entity.name}
                tabIndex={0}
                role="button"
                onClick={() => onSelectEntity(entity)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    onSelectEntity(entity);
                  }
                }}
                className="border rounded-xl p-4 bg-card hover:bg-muted/30 focus:outline-none focus:ring-2 focus:ring-primary transition-all flex flex-col gap-2 cursor-pointer"
              >
                <div className="flex items-center justify-between">
                  <div className="font-semibold text-sm font-mono text-primary">
                    {entity.name}
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-muted-foreground">
                      {(entity.fields || []).length} fields
                    </span>
                    <button
                      type="button"
                      aria-label={`Delete entity ${entity.name}`}
                      onClick={(e) => {
                        e.stopPropagation();
                        onDeleteEntity(entity.name);
                      }}
                      className="text-muted-foreground hover:text-destructive p-1 rounded"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </div>
                </div>

                <div className="flex flex-wrap gap-1.5 pt-1">
                  {(entity.fields || []).map((f) => (
                    <span
                      key={f.name}
                      className="text-[11px] bg-muted px-2 py-0.5 rounded font-mono text-muted-foreground"
                    >
                      {f.name} ({f.type})
                    </span>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      {/* Blocks Section */}
      <section aria-labelledby="blocks-heading" className="space-y-4">
        <div className="flex items-center gap-2 border-b pb-2">
          <Sliders className="h-5 w-5 text-primary" />
          <h2 id="blocks-heading" className="text-base font-semibold">
            Executable Blocks ({blocks.length})
          </h2>
        </div>

        {blocks.length === 0 ? (
          <p className="text-xs text-muted-foreground italic">No blocks configured.</p>
        ) : (
          <div className="grid gap-3">
            {blocks.map((block) => (
              <div
                key={block.id}
                tabIndex={0}
                role="button"
                onClick={() => onSelectBlock(block)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    onSelectBlock(block);
                  }
                }}
                className="border rounded-xl p-4 bg-card hover:bg-muted/30 focus:outline-none focus:ring-2 focus:ring-primary transition-all flex flex-col gap-2 cursor-pointer"
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="font-semibold text-sm font-mono">{block.id}</span>
                    <span className="text-[10px] uppercase font-mono px-1.5 py-0.5 bg-muted rounded">
                      {block.type}
                    </span>
                  </div>
                  <button
                    type="button"
                    aria-label={`Delete block ${block.id}`}
                    onClick={(e) => {
                      e.stopPropagation();
                      onDeleteBlock(block.id);
                    }}
                    className="text-muted-foreground hover:text-destructive p-1 rounded"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>

                <div className="text-xs text-muted-foreground font-mono truncate">
                  {JSON.stringify(block.params || {})}
                </div>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
