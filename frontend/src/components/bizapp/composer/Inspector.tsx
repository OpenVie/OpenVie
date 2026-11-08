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

import { useState } from "react";
import { useTranslations } from "next-intl";
import { Plus, Trash2, X } from "lucide-react";
import type { BizAppEntity, BizAppBlock, BizAppField } from "@/types";
import { Button } from "@/components/ui/button";
import { reportViewsOf } from "@/lib/bizapp-report";
import { blockDisplayName } from "@/lib/bizapp-block";
import { ReportBuilder } from "./ReportBuilder";

interface InspectorProps {
  selectedNode: { type: "entity" | "block"; data: BizAppEntity | BizAppBlock } | null;
  onUpdateEntity: (entity: BizAppEntity) => void;
  onUpdateBlock: (block: BizAppBlock) => void;
  onDeleteNode: () => void;
  onClose: () => void;
  entities?: BizAppEntity[];
}

export function Inspector({
  selectedNode,
  onUpdateEntity,
  onUpdateBlock,
  onDeleteNode,
  onClose,
  entities = [],
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
          entities={entities}
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
        <div className="space-y-2 overflow-x-hidden pr-1">
          {(entity.fields || []).map((f, i) => (
            <div key={i} className="border rounded p-2 bg-muted/20 space-y-1.5 overflow-hidden">
              <div className="flex min-w-0 gap-2">
                <input
                  type="text"
                  value={f.name}
                  onChange={(e) => updateField(i, { name: e.target.value })}
                  placeholder="name"
                  className="min-w-0 flex-1 border rounded px-1.5 py-0.5 bg-background font-mono text-[11px]"
                />
                <select
                  value={f.type}
                  onChange={(e) => updateField(i, { type: e.target.value as BizAppField["type"] })}
                  className="shrink-0 border rounded px-1.5 py-0.5 bg-background text-[11px]"
                >
                  <option value="string">string</option>
                  <option value="number">number</option>
                  <option value="boolean">boolean</option>
                  <option value="enum">enum</option>
                  <option value="date">date</option>
                  <option value="ref">ref</option>
                </select>
                <button onClick={() => removeField(i)} className="shrink-0 text-muted-foreground hover:text-destructive">
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

type ApprovalPreset = {
  template: string;
  label: string;
  description: string;
  levels: 1 | 2;
};

const APPROVAL_PRESETS: ApprovalPreset[] = [
  {
    template: "processes/bizapp-single-approval.bpmn20.xml",
    label: "Single-level approval",
    description: "One approver group reviews the record and makes the final decision.",
    levels: 1,
  },
  {
    template: "processes/bizapp-two-level-approval.bpmn20.xml",
    label: "Two-level approval",
    description: "First-level review, then the organization owner gives the final decision.",
    levels: 2,
  },
];

export const DEFAULT_APPROVAL_TEMPLATE = APPROVAL_PRESETS[0].template;

function BlockInspector({
  block,
  onChange,
  entities = [],
}: {
  block: BizAppBlock;
  onChange: (b: BizAppBlock) => void;
  entities?: BizAppEntity[];
}) {
  const updateParam = (key: string, value: unknown) => {
    const updated = { ...(block.params || {}), [key]: value };
    onChange({ ...block, params: updated });
  };

  return (
    <div className="space-y-4">
      <div className="rounded-lg border bg-muted/20 p-2.5">
        <div className="flex items-center justify-between gap-2">
          <p className="font-semibold text-sm">{blockDisplayName(block.type)}</p>
          <span className="shrink-0 rounded bg-muted px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-muted-foreground">
            {block.type}
          </span>
        </div>
        <details className="mt-1 text-[10px] text-muted-foreground">
          <summary className="cursor-pointer">Advanced</summary>
          <p className="mt-1 break-all font-mono">Internal ID: {block.id}</p>
        </details>
      </div>

      {block.type === "approval_workitem" && (() => {
        const currentTemplate = String(block.params?.bpmnTemplate || DEFAULT_APPROVAL_TEMPLATE);
        const activePreset = APPROVAL_PRESETS.find((preset) => preset.template === currentTemplate);
        const twoLevel = activePreset?.levels === 2;
        const selectPreset = (template: string) => {
          const preset = APPROVAL_PRESETS.find((candidate) => candidate.template === template);
          const params: Record<string, unknown> = { ...(block.params || {}), bpmnTemplate: template };
          if (preset?.levels === 2 && !params.candidateRole) {
            params.candidateRole = "WORKSPACE_ADMIN";
          }
          onChange({ ...block, params });
        };
        return (
          <div className="space-y-3 border-t pt-3">
            <div>
              <label className="font-medium text-muted-foreground">Approval process</label>
              <select
                value={currentTemplate}
                onChange={(e) => selectPreset(e.target.value)}
                className="w-full mt-1 border rounded px-2 py-1 bg-background text-xs"
              >
                {APPROVAL_PRESETS.map((preset) => (
                  <option key={preset.template} value={preset.template}>
                    {preset.label}
                  </option>
                ))}
                {!activePreset && (
                  <option value={currentTemplate}>Custom process (existing)</option>
                )}
              </select>
              <p className="mt-1 text-[11px] text-muted-foreground">
                {activePreset?.description || "This block uses a custom approval process."}
              </p>
            </div>
            <div>
              <label className="font-medium text-muted-foreground">
                {twoLevel ? "First-level approver" : "Approver role"}
              </label>
              <select
                value={String(block.params?.candidateRole || "WORKSPACE_ADMIN")}
                onChange={(e) => updateParam("candidateRole", e.target.value)}
                className="w-full mt-1 border rounded px-2 py-1 bg-background text-xs"
              >
                <option value="WORKSPACE_ADMIN">Workspace admin</option>
                <option value="ORG_OWNER">Organization owner</option>
                <option value="MEMBER">Member</option>
              </select>
              {twoLevel && (
                <p className="mt-1 text-[11px] text-muted-foreground">
                  Second level: Organization owner (final approval).
                </p>
              )}
            </div>
            {twoLevel && (
              <p className="rounded border bg-muted/20 px-2 py-1.5 text-[11px] text-muted-foreground">
                Submitted → first-level approver → organization owner → decision recorded
              </p>
            )}
          </div>
        );
      })()}

      {block.type === "external_call" && (
        <div className="space-y-3 border-t pt-3">
          <div>
            <label className="font-medium text-muted-foreground">API URL</label>
            <input
              type="text"
              value={String(block.params?.url || "")}
              onChange={(e) => updateParam("url", e.target.value)}
              placeholder="https://api.example.com/v1"
              className="w-full mt-1 border rounded px-2 py-1 bg-background font-mono text-[11px]"
            />
          </div>
          <div>
            <label className="font-medium text-muted-foreground">Credential name</label>
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
          <RecipientListField
            value={Array.isArray(block.params?.to) ? (block.params.to as string[]) : []}
            entities={entities}
            onChange={(value) => updateParam("to", value)}
          />
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

      {block.type === "report" && (
        <ReportBuilder
          views={reportViewsOf(block)}
          entities={entities}
          onChange={(views) => updateParam("views", views)}
        />
      )}

      {block.type === "webhook_out" && (
        <WebhookEventsField
          value={Array.isArray(block.params?.events) ? (block.params.events as string[]) : []}
          onChange={(events) => updateParam("events", events)}
        />
      )}

      {block.type === "knowledge_base" && (
        <div className="rounded-lg border bg-muted/20 p-3 space-y-1 text-[11px] text-muted-foreground">
          <p className="font-medium text-foreground">Knowledge base is automatic</p>
          <p>
            OpenVie creates a dedicated knowledge base for this app when records are projected
            or the assistant is used. There is nothing technical to configure here.
          </p>
        </div>
      )}

      <AdditionalParamsEditor block={block} onChange={onChange} />
    </div>
  );
}

const HANDLED_PARAMS: Record<string, Set<string>> = {
  approval_workitem: new Set(["bpmnTemplate", "candidateRole"]),
  notify: new Set(["to", "title", "subject"]),
  external_call: new Set(["url", "credentialRef"]),
  assistant: new Set(["instructions"]),
  report: new Set(["views"]),
  webhook_out: new Set(["events"]),
  knowledge_base: new Set(["graphExtraction", "ocrEnabled"]),
};


const WEBHOOK_EVENTS: Array<{ value: string; label: string; description: string }> = [
  { value: "record.created", label: "Record created", description: "A new record is saved." },
  { value: "record.updated", label: "Record updated", description: "An existing record is edited." },
  { value: "record.archived", label: "Record archived", description: "A record is removed from active use." },
  { value: "record.submitted", label: "Submitted for approval", description: "A record enters the approval flow." },
  { value: "record.decided", label: "Approval decided", description: "A record is approved or rejected." },
];

function WebhookEventsField({
  value,
  onChange,
}: {
  value: string[];
  onChange: (events: string[]) => void;
}) {
  const toggle = (event: string, checked: boolean) => {
    onChange(checked ? [...value, event] : value.filter((candidate) => candidate !== event));
  };
  return (
    <div className="space-y-2 border-t pt-3">
      <label className="font-medium text-muted-foreground">Events to send</label>
      <div className="space-y-1.5">
        {WEBHOOK_EVENTS.map((event) => (
          <label
            key={event.value}
            className="flex items-start gap-2 rounded border bg-muted/10 p-2 cursor-pointer"
          >
            <input
              type="checkbox"
              className="mt-0.5"
              checked={value.includes(event.value)}
              onChange={(changeEvent) => toggle(event.value, changeEvent.target.checked)}
            />
            <span>
              <span className="block text-[11px] font-medium">{event.label}</span>
              <span className="block text-[10px] text-muted-foreground">{event.description}</span>
            </span>
          </label>
        ))}
      </div>
      {value.length === 0 && (
        <p className="rounded border border-amber-500/40 bg-amber-500/10 p-2 text-[10px] text-amber-700 dark:text-amber-300">
          Choose at least one event. The webhook block is not valid without one.
        </p>
      )}
      <p className="text-[10px] text-muted-foreground">
        Your organization owner configures the delivery destination and signing secret. This block
        chooses which app events are published.
      </p>
    </div>
  );
}

const FRIENDLY_LABELS: Record<string, string> = {
  bpmnTemplate: "Approval flow",
  candidateRole: "Approver role",
  to: "Recipients",
  title: "Title",
  subject: "Email subject",
  url: "API URL",
  method: "HTTP method",
  credentialRef: "Credential",
  bodyTemplate: "Request body",
  responseMap: "Response mapping",
  sink: "Store result in",
  schedule: "Polling schedule",
  protocol: "Protocol",
  brokerUrl: "Broker URL",
  topic: "MQTT topic",
  events: "Webhook events",
  views: "Report views",
  instructions: "Instructions",
  sla: "SLA",
  reminder: "Reminder after",
  escalation: "Escalate after",
  allowedHosts: "Allowed hosts",
  auth: "Authentication",
  mapping: "Field mapping",
  entity: "Entity",
  dedupeKey: "Deduplication key",
  timeout: "Timeout",
  retry: "Retry policy",
  qos: "QoS",
  maxMessages: "Maximum messages",
};

function friendlyLabel(key: string): string {
  if (FRIENDLY_LABELS[key]) return FRIENDLY_LABELS[key];
  return key
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .replace(/\b\w/g, (char) => char.toUpperCase());
}

function AdditionalParamsEditor({
  block,
  onChange,
}: {
  block: BizAppBlock;
  onChange: (b: BizAppBlock) => void;
}) {
  const handled = HANDLED_PARAMS[block.type] ?? new Set<string>();
  const entries = Object.entries(block.params || {}).filter(([key]) => !handled.has(key));
  if (entries.length === 0) return null;

  const update = (key: string, value: unknown) => {
    onChange({ ...block, params: { ...(block.params || {}), [key]: value } });
  };

  return (
    <div className="space-y-3 border-t pt-3">
      <label className="font-medium text-muted-foreground">Additional settings</label>
      {entries.map(([key, value]) => (
        <ParamField key={key} name={key} value={value} onChange={(next) => update(key, next)} />
      ))}
    </div>
  );
}

function ParamField({
  name,
  value,
  onChange,
}: {
  name: string;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  if (typeof value === "boolean") {
    return (
      <label className="flex items-center gap-2 text-[11px]">
        <input type="checkbox" checked={value} onChange={(e) => onChange(e.target.checked)} />
        <span>{friendlyLabel(name)}</span>
      </label>
    );
  }
  if (typeof value === "number") {
    return (
      <div>
        <label className="font-medium text-muted-foreground">{friendlyLabel(name)}</label>
        <input
          type="number"
          value={value}
          onChange={(e) => onChange(e.target.value === "" ? "" : Number(e.target.value))}
          className="w-full mt-1 border rounded px-2 py-1 bg-background"
        />
      </div>
    );
  }
  if (Array.isArray(value)) {
    if (value.every((item) => typeof item === "string")) {
      return <StringListField name={name} value={value as string[]} onChange={onChange} />;
    }
    return <ObjectListField name={name} value={value} onChange={onChange} />;
  }
  if (value && typeof value === "object") {
    return (
      <div className="rounded border bg-muted/10 p-2 space-y-2">
        <p className="font-medium text-muted-foreground">{friendlyLabel(name)}</p>
        <ObjectFields value={value as Record<string, unknown>} onChange={onChange} />
      </div>
    );
  }
  const text = value == null ? "" : String(value);
  const multiline = /body|template|instructions|subject|reason/i.test(name) || text.length > 80;
  return (
    <div>
      <label className="font-medium text-muted-foreground">{friendlyLabel(name)}</label>
      {multiline ? (
        <textarea
          rows={3}
          value={text}
          onChange={(e) => onChange(e.target.value)}
          className="w-full mt-1 border rounded px-2 py-1 bg-background font-mono text-[11px]"
        />
      ) : (
        <input
          type="text"
          value={text}
          onChange={(e) => onChange(e.target.value)}
          className="w-full mt-1 border rounded px-2 py-1 bg-background font-mono text-[11px]"
        />
      )}
    </div>
  );
}

function ObjectFields({
  value,
  onChange,
}: {
  value: Record<string, unknown>;
  onChange: (value: Record<string, unknown>) => void;
}) {
  const update = (key: string, next: unknown) => onChange({ ...value, [key]: next });
  return (
    <div className="space-y-2">
      {Object.entries(value).map(([key, child]) => (
        <ParamField key={key} name={key} value={child} onChange={(next) => update(key, next)} />
      ))}
    </div>
  );
}

function RecipientListField({
  value,
  entities,
  onChange,
}: {
  value: string[];
  entities: BizAppEntity[];
  onChange: (value: string[]) => void;
}) {
  const [draft, setDraft] = useState("");
  const suggestions = [
    { value: "creator", label: "Record creator" },
    ...entities.flatMap((entity) =>
      (entity.fields || [])
        .filter((field) => /email|mail/i.test(field.name) || /email/i.test(field.format || ""))
        .map((field) => ({
          value: `record.${field.name}`,
          label: `${entity.label?.en || entity.label?.vi || entity.name} · ${
            field.label?.en || field.label?.vi || friendlyLabel(field.name)
          }`,
        })),
    ),
  ];

  const add = () => {
    const next = draft.trim();
    if (!next || value.includes(next)) return;
    onChange([...value, next]);
    setDraft("");
  };

  return (
    <div className="space-y-2">
      <label className="font-medium text-muted-foreground">Recipients</label>
      {value.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {value.map((recipient, index) => (
            <span
              key={`${recipient}-${index}`}
              title={recipient}
              className="inline-flex items-center gap-1 rounded-full border bg-muted px-2 py-0.5 text-[11px]"
            >
              {recipientLabel(recipient, entities)}
              <button
                type="button"
                onClick={() => onChange(value.filter((_, i) => i !== index))}
                className="text-muted-foreground hover:text-destructive"
                title="Remove recipient"
              >
                <X className="h-3 w-3" />
              </button>
            </span>
          ))}
        </div>
      )}
      <div className="flex min-w-0 gap-1.5">
        <input
          type="text"
          list="recipient-suggestions"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              event.preventDefault();
              add();
            }
          }}
          placeholder="Choose a person or enter an email"
          className="min-w-0 flex-1 border rounded px-2 py-1 bg-background text-[11px]"
        />
        <datalist id="recipient-suggestions">
          {suggestions.map((suggestion) => (
            <option key={suggestion.value} value={suggestion.value}>
              {suggestion.label}
            </option>
          ))}
        </datalist>
        <Button type="button" variant="outline" size="sm" onClick={add} disabled={!draft.trim()}>
          <Plus className="h-3.5 w-3.5" /> Add
        </Button>
      </div>
      <p className="text-[10px] text-muted-foreground">
        Pick “Record creator”, an email field from the app, or type an email address.
      </p>
    </div>
  );
}

function recipientLabel(value: string, entities: BizAppEntity[]): string {
  if (value === "creator") return "Record creator";
  if (value.startsWith("record.")) {
    const fieldName = value.slice("record.".length);
    for (const entity of entities) {
      const field = (entity.fields || []).find((candidate) => candidate.name === fieldName);
      if (field) {
        return field.label?.en || field.label?.vi || friendlyLabel(field.name);
      }
    }
    return friendlyLabel(fieldName);
  }
  if (value.startsWith("config.")) return `Config · ${friendlyLabel(value.slice("config.".length))}`;
  return value;
}

function StringListField({
  name,
  value,
  onChange,
}: {
  name: string;
  value: string[];
  onChange: (value: string[]) => void;
}) {
  const update = (index: number, next: string) => {
    const copy = [...value];
    copy[index] = next;
    onChange(copy);
  };
  return (
    <div className="space-y-1.5">
      <label className="font-medium text-muted-foreground">{friendlyLabel(name)}</label>
      {value.map((item, index) => (
        <div key={index} className="flex min-w-0 gap-1.5">
          <input
            type="text"
            value={item}
            onChange={(e) => update(index, e.target.value)}
            className="min-w-0 flex-1 border rounded px-2 py-1 bg-background font-mono text-[11px]"
          />
          <button
            type="button"
            onClick={() => onChange(value.filter((_, i) => i !== index))}
            className="shrink-0 text-muted-foreground hover:text-destructive"
            title="Remove"
          >
            <Trash2 className="h-3.5 w-3.5" />
          </button>
        </div>
      ))}
      <Button type="button" variant="outline" size="sm" onClick={() => onChange([...value, ""])}>
        <Plus className="h-3.5 w-3.5" /> Add
      </Button>
    </div>
  );
}

function ObjectListField({
  name,
  value,
  onChange,
}: {
  name: string;
  value: unknown[];
  onChange: (value: unknown[]) => void;
}) {
  return (
    <div className="space-y-2">
      <label className="font-medium text-muted-foreground">{friendlyLabel(name)}</label>
      {value.map((item, index) => (
        <div key={index} className="rounded border bg-muted/10 p-2 space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-[10px] uppercase tracking-wide text-muted-foreground">Item {index + 1}</span>
            <button
              type="button"
              onClick={() => onChange(value.filter((_, i) => i !== index))}
              className="text-muted-foreground hover:text-destructive"
              title="Remove"
            >
              <Trash2 className="h-3.5 w-3.5" />
            </button>
          </div>
          {item && typeof item === "object" && !Array.isArray(item) ? (
            <ObjectFields
              value={item as Record<string, unknown>}
              onChange={(next) => {
                const copy = [...value];
                copy[index] = next;
                onChange(copy);
              }}
            />
          ) : (
            <input
              type="text"
              value={String(item ?? "")}
              onChange={(e) => {
                const copy = [...value];
                copy[index] = e.target.value;
                onChange(copy);
              }}
              className="w-full border rounded px-2 py-1 bg-background font-mono text-[11px]"
            />
          )}
        </div>
      ))}
      <Button type="button" variant="outline" size="sm" onClick={() => onChange([...value, newObjectLike(value)])}>
        <Plus className="h-3.5 w-3.5" /> Add
      </Button>
    </div>
  );
}

function blankLike(value: unknown): unknown {
  if (Array.isArray(value)) return [];
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([key, child]) => [key, blankLike(child)]));
  }
  if (typeof value === "number") return 0;
  if (typeof value === "boolean") return false;
  return "";
}

function newObjectLike(values: unknown[]): Record<string, unknown> {
  const template = values.find(
    (item) => item && typeof item === "object" && !Array.isArray(item),
  ) as Record<string, unknown> | undefined;
  if (!template) return {};
  return Object.fromEntries(Object.entries(template).map(([key, value]) => [key, blankLike(value)]));
}
