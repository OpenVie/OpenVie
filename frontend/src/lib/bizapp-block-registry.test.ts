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

import { describe, expect, it } from "vitest";
import {
  blockDefaults,
  blockTypes,
  isHookableBlockType,
  paletteBlockTypes,
} from "@/lib/bizapp-block-registry";

describe("bizapp block registry", () => {
  it("is the single source for all allowed block types", () => {
    expect(blockTypes()).toEqual([
      "approval_workitem", "assistant", "knowledge_base", "report", "notify",
      "webhook_out", "external_call",
    ]);
    expect(paletteBlockTypes()).toEqual([
      "approval_workitem", "assistant", "knowledge_base", "report", "notify",
      "webhook_out", "external_call",
    ]);
  });

  it("marks only executable blocks as hookable", () => {
    for (const type of ["approval_workitem", "notify", "webhook_out", "external_call"]) {
      expect(isHookableBlockType(type)).toBe(true);
    }
    for (const type of ["assistant", "knowledge_base", "report"]) {
      expect(isHookableBlockType(type)).toBe(false);
    }
  });

  it("returns fresh default params so one node cannot mutate another", () => {
    const first = blockDefaults("notify");
    const second = blockDefaults("notify");
    expect(first).toEqual({ to: ["creator"] });
    (first.to as string[]).push("a@b.co");
    expect(second).toEqual({ to: ["creator"] });
    expect(blockDefaults("approval_workitem")).toEqual({
      bpmnTemplate: "processes/bizapp-single-approval.bpmn20.xml",
      candidateRole: "WORKSPACE_ADMIN",
    });
  });
});
