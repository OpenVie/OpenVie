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

const BLOCK_LABELS: Record<string, string> = {
  approval_workitem: "Approval",
  assistant: "Assistant",
  knowledge_base: "Knowledge base",
  report: "Report",
  notify: "Notifications",
  webhook_out: "Webhook",
  external_call: "External API",
  registry: "Registry view",
  form: "Form view",
  timeline: "Timeline view",
};

export function blockDisplayName(type: string): string {
  return BLOCK_LABELS[type] || type.replace(/[_-]+/g, " ");
}

export function blockSummary(block: BizAppBlock): string {
  const params = block.params || {};
  switch (block.type) {
    case "approval_workitem":
      return String(params.bpmnTemplate || "").includes("two-level")
        ? "Two-level approval"
        : "Single-level approval";
    case "report": {
      const count = reportViewsOf(block).length;
      return count === 0 ? "Needs a report" : `${count} report${count === 1 ? "" : "s"}`;
    }
    case "webhook_out": {
      const events = Array.isArray(params.events) ? params.events.length : 0;
      return events === 0 ? "Choose events" : `${events} event${events === 1 ? "" : "s"}`;
    }
    case "knowledge_base":
      return "Created automatically";
    case "notify": {
      const recipients = Array.isArray(params.to) ? params.to.length : 0;
      return recipients === 0 ? "Needs recipients" : `${recipients} recipient${recipients === 1 ? "" : "s"}`;
    }
    case "external_call": {
      const protocol = typeof params.protocol === "string" ? params.protocol : "http";
      if (protocol === "mqtt") {
        const hasBroker = typeof params.brokerUrl === "string" && params.brokerUrl.trim().length > 0;
        const hasTopic = typeof params.topic === "string" && params.topic.trim().length > 0;
        return hasBroker && hasTopic ? "MQTT topic configured" : "Needs a broker URL and topic";
      }
      return params.url ? "API call configured" : "Needs an API URL";
    }
    case "assistant":
      return params.instructions ? "Assistant configured" : "Assistant";
    default:
      return Object.keys(params).length ? "Configured" : "No setup needed";
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
  if (type === "webhook_out") {
    return { events: ["record.created", "record.updated"] };
  }
  if (type === "notify") {
    return { to: ["creator"] };
  }
  return {};
}
