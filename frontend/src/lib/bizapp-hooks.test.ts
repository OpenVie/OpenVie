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
import { nextHookLabel } from "@/lib/bizapp-hooks";

describe("nextHookLabel", () => {
  it("starts at onCreate and advances through the lifecycle", () => {
    expect(nextHookLabel([])).toBe("onCreate");
    expect(nextHookLabel(["onCreate"])).toBe("onUpdate");
    expect(nextHookLabel(["onCreate", "onUpdate"])).toBe("onDecide");
  });

  it("returns null once all three events are used", () => {
    expect(nextHookLabel(["onCreate", "onUpdate", "onDecide"])).toBeNull();
  });

  it("ignores unknown labels and fills the first free event", () => {
    expect(nextHookLabel(["custom", "onUpdate"])).toBe("onCreate");
  });
});
