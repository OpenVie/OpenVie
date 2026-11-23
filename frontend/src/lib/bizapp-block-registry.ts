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

import registry from "../../../contracts/bizapp/blocks.v1.json";

export interface BizAppBlockDefinition {
  type: string;
  hookable: boolean;
  palette: boolean;
  defaults: Record<string, unknown>;
  paramSchema: string | null;
}

const BLOCKS = (registry.blocks as BizAppBlockDefinition[]).slice();
const BY_TYPE = new Map(BLOCKS.map((block) => [block.type, block]));
const HOOKABLE = new Set(BLOCKS.filter((block) => block.hookable).map((block) => block.type));

export function blockDefinitions(): readonly BizAppBlockDefinition[] {
  return BLOCKS;
}

export function blockDefinition(type: string): BizAppBlockDefinition | undefined {
  return BY_TYPE.get(type);
}

export function blockTypes(): string[] {
  return BLOCKS.map((block) => block.type);
}

export function paletteBlockTypes(): string[] {
  return BLOCKS.filter((block) => block.palette).map((block) => block.type);
}

export function isHookableBlockType(type: string | undefined): boolean {
  return type !== undefined && HOOKABLE.has(type);
}

/** Fresh defaults: callers mutate params, so shared arrays/objects are unsafe. */
export function blockDefaults(type: string): Record<string, unknown> {
  const defaults = blockDefinition(type)?.defaults ?? {};
  return JSON.parse(JSON.stringify(defaults)) as Record<string, unknown>;
}
