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

import type { BizAppBlock } from "@/types";
import { defaultReportView, reportViewsOf, type ReportView } from "@/lib/bizapp-report";
import { blockDefaults } from "@/lib/bizapp-block-registry";

export { isHookableBlockType } from "@/lib/bizapp-block-registry";

const BLOCK_NAME_KEYS: Record<string, string> = {
  approval_workitem: "blockNames.approval_workitem",
  assistant: "blockNames.assistant",
  knowledge_base: "blockNames.knowledge_base",
  report: "blockNames.report",
  notify: "blockNames.notify",
  webhook_out: "blockNames.webhook_out",
  external_call: "blockNames.external_call",
  registry: "blockNames.registry",
  form: "blockNames.form",
  timeline: "blockNames.timeline",
};

export function blockNameKey(type: string): string | null {
  return BLOCK_NAME_KEYS[type] ?? null;
}

export interface BlockSummaryMessage {
  key: string;
  values?: Record<string, string | number>;
}

/** Translation descriptor; components resolve it against `BizApps.composer`. */
export function blockSummary(block: BizAppBlock): BlockSummaryMessage {
  const params = block.params || {};
  switch (block.type) {
    case "approval_workitem":
      return {
        key: String(params.bpmnTemplate || "").includes("two-level")
          ? "blockSummary.approvalTwo"
          : "blockSummary.approvalSingle",
      };
    case "report": {
      const count = reportViewsOf(block).length;
      return count === 0
        ? { key: "blockSummary.reportMissing" }
        : { key: "blockSummary.reportCount", values: { count } };
    }
    case "webhook_out": {
      const count = Array.isArray(params.events) ? params.events.length : 0;
      return count === 0
        ? { key: "blockSummary.webhookMissing" }
        : { key: "blockSummary.webhookCount", values: { count } };
    }
    case "knowledge_base":
      return { key: "blockSummary.knowledgeCreated" };
    case "notify": {
      const count = Array.isArray(params.to) ? params.to.length : 0;
      return count === 0
        ? { key: "blockSummary.notifyMissing" }
        : { key: "blockSummary.notifyCount", values: { count } };
    }
    case "external_call": {
      const protocol = typeof params.protocol === "string" ? params.protocol : "http";
      if (protocol === "mqtt") {
        const hasBroker = typeof params.brokerUrl === "string" && params.brokerUrl.trim().length > 0;
        const hasTopic = typeof params.topic === "string" && params.topic.trim().length > 0;
        return hasBroker && hasTopic
          ? { key: "blockSummary.mqttConfigured" }
          : { key: "blockSummary.mqttMissing" };
      }
      return params.url
        ? { key: "blockSummary.httpConfigured" }
        : { key: "blockSummary.httpMissing" };
    }
    case "assistant":
      return params.instructions
        ? { key: "blockSummary.assistantConfigured" }
        : { key: "blockSummary.assistantDefault" };
    default:
      return Object.keys(params).length
        ? { key: "blockSummary.configured" }
        : { key: "blockSummary.noSetup" };
  }
}

/** Valid starter parameters so a newly dropped block is not born invalid. */
export function defaultBlockParams(
  type: string,
  entities: import("@/types").BizAppEntity[],
  existingViews: ReportView[] = [],
): Record<string, unknown> {
  if (type === "report") {
    const view = defaultReportView(entities, existingViews);
    return { views: view ? [view] : [] };
  }
  return blockDefaults(type);
}
