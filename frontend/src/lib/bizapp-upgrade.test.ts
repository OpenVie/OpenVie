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
import type { BizAppManifest } from "@/types";
import { upgradeManifestVersion } from "@/lib/bizapp-upgrade";

const manifest: BizAppManifest = {
  schema: "bizapp.v1",
  kind: "inventory",
  name: { en: "Inventory" },
  version: 1,
  entities: [],
};

describe("upgradeManifestVersion", () => {
  it("bumps from the installed version when the draft was never re-versioned", () => {
    expect(upgradeManifestVersion(manifest, 1).version).toBe(2);
  });

  it("keeps a draft version that is already higher", () => {
    expect(upgradeManifestVersion({ ...manifest, version: 5 }, 2).version).toBe(5);
  });
});
