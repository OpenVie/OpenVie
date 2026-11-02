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

import { useTranslations } from "next-intl";
import { Plus, Trash2, X } from "lucide-react";
import type { BizAppEntity, BizAppBlock, BizAppField } from "@/types";
import { Button } from "@/components/ui/button";

interface InspectorProps {
  selectedNode: { type: "entity" | "block"; data: BizAppEntity | BizAppBlock } | null;
  onUpdateEntity: (entity: BizAppEntity) => void;
  onUpdateBlock: (block: BizAppBlock) => void;
  onDeleteNode: () => void;
  onClose: () => void;
}

export function Inspector({
  selectedNode,
  onUpdateEntity,
  onUpdateBlock,
  onDeleteNode,
  onClose,
}: InspectorProps) {
  const t = useTranslations("BizApps.composer");

  if (!selectedNode) {
    return (
      <div className="w-80 border-l bg-muted/10 p-4 text-xs text-muted-foreground flex flex-col justify-center items-center text-center">
        <p>{t("noSelection")}</p>
      </div>
    );
  }

  return (
    <div className="w-80 border-l bg-card p-4 space-y-4 overflow-y-auto text-xs shrink-0 flex flex-col">
      <div className="flex items-center justify-between border-b pb-2">
        <h3 className="font-semibold text-sm">
          {selectedNode.type === "entity" ? "Entity Properties" : "Block Properties"}
        </h3>
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="icon-sm" onClick={onDeleteNode} title="Delete">
            <Trash2 className="h-4 w-4 text-destructive" />
          </Button>
          <Button variant="ghost" size="icon-sm" onClick={onClose} title="Close">
            <X className="h-4 w-4" />
          </Button>
        </div>
      </div>

      {selectedNode.type === "entity" ? (
        <EntityInspector
          entity={selectedNode.data as BizAppEntity}
          onChange={onUpdateEntity}
        />
      ) : (
        <BlockInspector
          block={selectedNode.data as BizAppBlock}
          onChange={onUpdateBlock}
        />
      )}
    </div>
  );
}

function EntityInspector({
  entity,
  onChange,
}: {
  entity: BizAppEntity;
  onChange: (e: BizAppEntity) => void;
}) {
  const updateField = (idx: number, patch: Partial<BizAppField>) => {
    const updated = [...(entity.fields || [])];
    updated[idx] = { ...updated[idx], ...patch };
    onChange({ ...entity, fields: updated });
  };

  const addField = () => {
    const newF: BizAppField = {
      name: `field${(entity.fields || []).length + 1}`,
      type: "string",
      required: false,
    };
    onChange({ ...entity, fields: [...(entity.fields || []), newF] });
  };

  const removeField = (idx: number) => {
    const updated = (entity.fields || []).filter((_, i) => i !== idx);
    onChange({ ...entity, fields: updated });
  };

  return (
    <div className="space-y-4">
      <div>
        <label className="font-medium text-muted-foreground">Entity Name</label>
        <input
          type="text"
          value={entity.name}
          onChange={(e) => onChange({ ...entity, name: e.target.value })}
          className="w-full mt-1 border rounded px-2 py-1 bg-background"
        />
      </div>

      <div>
        <label className="font-medium text-muted-foreground">Label (EN)</label>
        <input
          type="text"
          value={entity.label?.en || ""}
          onChange={(e) =>
            onChange({
              ...entity,
              label: { ...entity.label, en: e.target.value, vi: entity.label?.vi || e.target.value },
            })
          }
          className="w-full mt-1 border rounded px-2 py-1 bg-background"
        />
      </div>

      <div>
        <div className="flex items-center justify-between mb-1.5">
          <label className="font-medium text-muted-foreground">Fields</label>
          <Button variant="ghost" size="icon-sm" onClick={addField}>
            <Plus className="h-3.5 w-3.5" />
          </Button>
        </div>
        <div className="space-y-2 max-h-60 overflow-y-auto pr-1">
          {(entity.fields || []).map((f, i) => (
            <div key={i} className="border rounded p-2 bg-muted/20 space-y-1.5">
              <div className="flex gap-2">
                <input
                  type="text"
                  value={f.name}
                  onChange={(e) => updateField(i, { name: e.target.value })}
                  placeholder="name"
                  className="flex-1 border rounded px-1.5 py-0.5 bg-background font-mono text-[11px]"
                />
                <select
                  value={f.type}
                  onChange={(e) => updateField(i, { type: e.target.value as BizAppField["type"] })}
                  className="border rounded px-1.5 py-0.5 bg-background text-[11px]"
                >
                  <option value="string">string</option>
                  <option value="number">number</option>
                  <option value="boolean">boolean</option>
                  <option value="enum">enum</option>
                  <option value="date">date</option>
                  <option value="ref">ref</option>
                </select>
                <button onClick={() => removeField(i)} className="text-muted-foreground hover:text-destructive">
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </div>
              <div className="flex items-center gap-4 text-[11px]">
                <label className="flex items-center gap-1 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={Boolean(f.required)}
                    onChange={(e) => updateField(i, { required: e.target.checked })}
                  />
                  <span>Required</span>
                </label>
                <label className="flex items-center gap-1 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={Boolean(f.pii)}
                    onChange={(e) => updateField(i, { pii: e.target.checked })}
                  />
                  <span>PII</span>
                </label>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function BlockInspector({
  block,
  onChange,
}: {
  block: BizAppBlock;
  onChange: (b: BizAppBlock) => void;
}) {
  const updateParam = (key: string, value: unknown) => {
    const updated = { ...(block.params || {}), [key]: value };
    onChange({ ...block, params: updated });
  };

  return (
    <div className="space-y-4">
      <div>
        <label className="font-medium text-muted-foreground">Block ID</label>
        <input
          type="text"
          value={block.id}
          onChange={(e) => onChange({ ...block, id: e.target.value })}
          className="w-full mt-1 border rounded px-2 py-1 bg-background"
        />
      </div>

      <div>
        <label className="font-medium text-muted-foreground">Block Type</label>
        <input
          type="text"
          value={block.type}
          disabled
          className="w-full mt-1 border rounded px-2 py-1 bg-muted text-muted-foreground font-mono"
        />
      </div>

      {block.type === "external_call" && (
        <div className="space-y-3 border-t pt-3">
          <div>
            <label className="font-medium text-muted-foreground">URL</label>
            <input
              type="text"
              value={String(block.params?.url || "")}
              onChange={(e) => updateParam("url", e.target.value)}
              placeholder="https://api.example.com/v1"
              className="w-full mt-1 border rounded px-2 py-1 bg-background font-mono text-[11px]"
            />
          </div>
          <div>
            <label className="font-medium text-muted-foreground">Credential Ref (Vault)</label>
            <input
              type="text"
              value={String(block.params?.credentialRef || "")}
              onChange={(e) => updateParam("credentialRef", e.target.value)}
              placeholder="vault-key-name"
              className="w-full mt-1 border rounded px-2 py-1 bg-background font-mono text-[11px]"
            />
          </div>
        </div>
      )}

      {block.type === "notify" && (
        <div className="space-y-3 border-t pt-3">
          <div>
            <label className="font-medium text-muted-foreground">Title</label>
            <input
              type="text"
              value={String(block.params?.title || "")}
              onChange={(e) => updateParam("title", e.target.value)}
              className="w-full mt-1 border rounded px-2 py-1 bg-background"
            />
          </div>
          <div>
            <label className="font-medium text-muted-foreground">Subject</label>
            <input
              type="text"
              value={String(block.params?.subject || "")}
              onChange={(e) => updateParam("subject", e.target.value)}
              className="w-full mt-1 border rounded px-2 py-1 bg-background"
            />
          </div>
        </div>
      )}

      {block.type === "assistant" && (
        <div className="space-y-3 border-t pt-3">
          <div>
            <label className="font-medium text-muted-foreground">Instructions</label>
            <textarea
              rows={3}
              value={String(block.params?.instructions || "")}
              onChange={(e) => updateParam("instructions", e.target.value)}
              className="w-full mt-1 border rounded px-2 py-1 bg-background text-xs"
            />
          </div>
        </div>
      )}
    </div>
  );
}
