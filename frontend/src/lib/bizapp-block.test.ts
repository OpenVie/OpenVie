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
import type { BizAppBlock } from "@/types";
import {
  blockNameKey,
  blockSummary,
  isHookableBlockType,
} from "@/lib/bizapp-block";

function block(params: Record<string, unknown>, type = "external_call"): BizAppBlock {
  return { id: "pollSensor", type, params };
}

describe("isHookableBlockType", () => {
  it("accepts only blocks the manifest validator allows as hook targets", () => {
    for (const type of ["approval_workitem", "notify", "webhook_out", "external_call"]) {
      expect(isHookableBlockType(type)).toBe(true);
    }
  });

  it("rejects non-hookable functional blocks and removed presentation types", () => {
    for (const type of ["report", "assistant", "knowledge_base"]) {
      expect(isHookableBlockType(type)).toBe(false);
    }
    expect(isHookableBlockType(undefined)).toBe(false);
    expect(isHookableBlockType("toString")).toBe(false);
  });
});

describe("blockNameKey", () => {
  it("maps every allowed block type and leaves unknown types unstyled", () => {
    for (const type of [
      "approval_workitem", "assistant", "knowledge_base", "report", "notify",
      "webhook_out", "external_call",
    ]) {
      expect(blockNameKey(type)).toBe(`blockNames.${type}`);
    }
    for (const type of ["registry", "form", "timeline"]) {
      expect(blockNameKey(type)).toBeNull();
    }
    expect(blockNameKey("unknown_block")).toBeNull();
  });
});

describe("blockSummary external_call", () => {
  it("recognises an MQTT block by brokerUrl and topic", () => {
    expect(blockSummary(block({
      protocol: "mqtt",
      brokerUrl: "tcp://localhost:1883",
      topic: "openvie/aq/aq-01/telemetry",
    }))).toEqual({ key: "blockSummary.mqttConfigured" });
  });

  it("asks for the MQTT addressing a valid block still needs", () => {
    expect(blockSummary(block({ protocol: "mqtt", brokerUrl: "tcp://localhost:1883" })))
      .toEqual({ key: "blockSummary.mqttMissing" });
    expect(blockSummary(block({ protocol: "mqtt", topic: " openvie/aq/+/telemetry " })))
      .toEqual({ key: "blockSummary.mqttMissing" });
  });

  it("keeps the HTTP wording for url-based calls", () => {
    expect(blockSummary(block({ url: "https://gateway.test/readings" })))
      .toEqual({ key: "blockSummary.httpConfigured" });
    expect(blockSummary(block({}))).toEqual({ key: "blockSummary.httpMissing" });
    expect(blockSummary(block({ protocol: "http" }))).toEqual({ key: "blockSummary.httpMissing" });
  });

  it("returns count-bearing keys for reports and notifications", () => {
    expect(blockSummary(block({ views: [{ name: "a" }, { name: "b" }] }, "report")))
      .toEqual({ key: "blockSummary.reportCount", values: { count: 2 } });
    expect(blockSummary(block({ views: [] }, "report")))
      .toEqual({ key: "blockSummary.reportMissing" });
    expect(blockSummary(block({ to: ["creator", "a@b.co"] }, "notify")))
      .toEqual({ key: "blockSummary.notifyCount", values: { count: 2 } });
  });
});
