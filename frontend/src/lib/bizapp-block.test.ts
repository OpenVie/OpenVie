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
import { blockSummary } from "@/lib/bizapp-block";

function block(params: Record<string, unknown>, type = "external_call"): BizAppBlock {
  return { id: "pollSensor", type, params };
}

describe("blockSummary external_call", () => {
  it("recognises an MQTT block by brokerUrl and topic", () => {
    expect(blockSummary(block({
      protocol: "mqtt",
      brokerUrl: "tcp://localhost:1883",
      topic: "openvie/aq/aq-01/telemetry",
    }))).toBe("MQTT topic configured");
  });

  it("asks for the MQTT addressing a valid block still needs", () => {
    expect(blockSummary(block({ protocol: "mqtt", brokerUrl: "tcp://localhost:1883" })))
      .toBe("Needs a broker URL and topic");
    expect(blockSummary(block({ protocol: "mqtt", topic: " openvie/aq/+/telemetry " })))
      .toBe("Needs a broker URL and topic");
  });

  it("keeps the HTTP wording for url-based calls", () => {
    expect(blockSummary(block({ url: "https://gateway.test/readings" })))
      .toBe("API call configured");
    expect(blockSummary(block({}))).toBe("Needs an API URL");
    expect(blockSummary(block({ protocol: "http" }))).toBe("Needs an API URL");
  });
});
