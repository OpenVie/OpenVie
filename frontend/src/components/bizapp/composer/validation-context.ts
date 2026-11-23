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

import { createContext, useContext } from "react";
import type { BizAppManifest, BizAppValidationIssue } from "@/types";

const EMPTY_ERRORS: Readonly<Record<string, string>> = {};

export const ComposerValidationContext =
  createContext<Readonly<Record<string, string>>>(EMPTY_ERRORS);

/** Validation message for a React Flow node id (`entity-<name>` / `block-<id>`). */
export function useNodeValidationError(nodeId: string | undefined): string | undefined {
  const errors = useContext(ComposerValidationContext);
  return nodeId ? errors[nodeId] : undefined;
}

/**
 * Validation paths are index-based (`entities[0]`, `blocks[1].params.views[0]`)
 * so the composer can map them without transporting entity/block names back
 * from the API. Unknown or manifest-wide paths intentionally stay panel-only.
 */
export function validationErrorsByNode(
  manifest: Pick<BizAppManifest, "entities" | "blocks">,
  issues: BizAppValidationIssue[],
): Readonly<Record<string, string>> {
  const errors: Record<string, string> = {};
  for (const issue of issues) {
    // Paths carry stable names, not array indexes, so reordering nodes cannot
    // move an error onto the wrong card.
    const match = /^(entities|blocks)\.([^.[\]]+)/.exec(issue.path);
    if (!match) continue;
    const exists = match[1] === "entities"
      ? manifest.entities.some((entity) => entity.name === match[2])
      : manifest.blocks?.some((block) => block.id === match[2]);
    if (!exists) continue;
    const nodeId = match[1] === "entities" ? `entity-${match[2]}` : `block-${match[2]}`;
    errors[nodeId] = errors[nodeId] ? `${errors[nodeId]}; ${issue.message}` : issue.message;
  }
  return errors;
}
