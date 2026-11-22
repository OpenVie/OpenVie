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

export const LIFECYCLE_HOOKS = ["onCreate", "onUpdate", "onDecide"] as const;

export type LifecycleHook = (typeof LIFECYCLE_HOOKS)[number];

/** Next available event for a repeated entity → block connection. */
export function nextHookLabel(labels: string[]): LifecycleHook | null {
  const used = new Set(labels);
  return LIFECYCLE_HOOKS.find((hook) => !used.has(hook)) ?? null;
}
