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

/**
 * Translate validator wording into the next action a composer user can take.
 * Unknown messages stay intact so operators do not lose diagnostic detail.
 */
export function friendlyValidationError(message: string): string {
  if (message.includes("report block requires a non-empty views list")) {
    return "The Report block needs at least one report. Open the block and choose Create report.";
  }
  if (message.includes("webhook_out block requires events")) {
    return "The Webhook block needs at least one event. Open it and tick the events to send.";
  }
  if (message.includes("notify block requires a `to` list")) {
    return "The Notifications block needs at least one recipient.";
  }
  if (message.includes("approval_workitem.bpmnTemplate")) {
    return "The Approval block needs a process. Choose Single-level or Two-level approval.";
  }
  if (message.includes("references a missing report view")) {
    return "A report tab points at a report that no longer exists. Remove it or re-create the report.";
  }
  if (message.includes("references a missing entity")) {
    return "A view points at an entity that no longer exists. Re-select the entity in that view.";
  }
  if (message.includes("duplicate block id")) {
    return "Two blocks share the same internal ID. Delete one of them.";
  }
  return message;
}
