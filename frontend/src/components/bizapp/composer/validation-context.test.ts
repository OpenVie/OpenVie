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
import { validationErrorsByNode } from "./validation-context";
import type { BizAppBlock, BizAppEntity } from "@/types";

const entities: BizAppEntity[] = [
  { name: "devices", fields: [] },
  { name: "sites", fields: [] },
];
const blocks: BizAppBlock[] = [
  { id: "report1", type: "report", params: {} },
  { id: "notify1", type: "notify", params: {} },
];

describe("validationErrorsByNode", () => {
  it("maps entity and block paths to React Flow node ids", () => {
    const errors = validationErrorsByNode(
      { entities, blocks },
      [
        { path: "entities[0]", message: "entity label missing" },
        { path: "blocks[1].params.to", message: "notify needs recipients" },
      ],
    );

    expect(errors).toEqual({
      "entity-devices": "entity label missing",
      "block-notify1": "notify needs recipients",
    });
  });

  it("joins multiple errors on one node and ignores manifest-wide paths", () => {
    const errors = validationErrorsByNode(
      { entities, blocks },
      [
        { path: "entities[1]", message: "first" },
        { path: "entities[1].fields[0]", message: "second" },
        { path: "ui.views[0]", message: "panel only" },
        { path: "manifest", message: "panel only" },
      ],
    );

    expect(errors["entity-sites"]).toBe("first; second");
    expect(Object.keys(errors)).toEqual(["entity-sites"]);
  });

  it("ignores out-of-range indexes", () => {
    expect(validationErrorsByNode(
      { entities, blocks },
      [{ path: "blocks[4]", message: "stale" }],
    )).toEqual({});
  });
});
