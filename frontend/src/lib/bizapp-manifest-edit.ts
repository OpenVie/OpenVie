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

import type { BizAppEntity, BizAppManifest } from "@/types";
import { reportViewsOf, syncReportViews } from "@/lib/bizapp-report";

/**
 * Remove an entity and every UI/report reference that would otherwise become
 * an invalid dangling target. Ref fields on other entities are intentionally
 * kept: silently rewriting another entity's schema is worse than letting the
 * validator surface the dependency.
 */
export function removeEntityFromManifest(
  manifest: BizAppManifest,
  entityName: string,
): BizAppManifest {
  const previousBlocks = manifest.blocks ?? [];
  const entities = (manifest.entities ?? []).filter((entity) => entity.name !== entityName);

  const blocks = previousBlocks
    .map((block) => {
      if (block.type !== "report") return block;
      const views = reportViewsOf(block);
      const kept = views.filter((view) => view.entity !== entityName);
      if (kept.length === views.length) return block;
      return {
        ...block,
        params: { ...block.params, views: kept },
      };
    })
    .filter((block) => block.type !== "report" || reportViewsOf(block).length > 0);

  const remainingBlockIds = new Set(blocks.map((block) => block.id));
  const removedBlockIds = previousBlocks
    .filter((block) => !remainingBlockIds.has(block.id))
    .map((block) => block.id);

  const layout = { ...(manifest.ui?.layout ?? {}) };
  delete layout[entityName];
  removedBlockIds.forEach((blockId) => delete layout[blockId]);

  const views = (manifest.ui?.views ?? []).filter(
    (view) =>
      view !== `registry:${entityName}` &&
      view !== `form:${entityName}` &&
      view !== `timeline:${entityName}`,
  );

  return syncReportViews({
    ...manifest,
    entities,
    blocks,
    ui: { ...manifest.ui, views, layout },
  });
}

/**
 * Remove one block, its layout/assistant tab, and any entity hook that targets
 * it. Report tabs are pruned by syncReportViews.
 */
export function removeBlockFromManifest(
  manifest: BizAppManifest,
  blockId: string,
): BizAppManifest {
  const blocks = (manifest.blocks ?? []).filter((block) => block.id !== blockId);

  const entities = (manifest.entities ?? []).map((entity): BizAppEntity => {
    if (!entity.hooks) return entity;
    const hooks: Record<string, string[]> = {};
    Object.entries(entity.hooks).forEach(([hook, targets]) => {
      const kept = targets.filter((target) => target !== blockId);
      if (kept.length > 0) hooks[hook] = kept;
    });
    return {
      ...entity,
      hooks: Object.keys(hooks).length > 0 ? hooks : undefined,
    };
  });

  const layout = { ...(manifest.ui?.layout ?? {}) };
  delete layout[blockId];
  const views = (manifest.ui?.views ?? []).filter((view) => view !== `assistant:${blockId}`);

  return syncReportViews({
    ...manifest,
    entities,
    blocks,
    ui: { ...manifest.ui, views, layout },
  });
}
