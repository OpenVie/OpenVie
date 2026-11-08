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

import type { BizAppManifest } from "@/types";

/**
 * Upgrade requires a strictly higher manifest version. Drafts saved before an
 * install keep their old version, so bump from the installed version rather
 * than trusting the editor value.
 */
export function upgradeManifestVersion(
  manifest: BizAppManifest,
  installedVersion: number,
): BizAppManifest {
  return {
    ...manifest,
    version: Math.max(installedVersion + 1, manifest.version ?? 1),
  };
}
