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

import type { BizAppBlock, BizAppEntity, BizAppManifest } from "@/types";

export type ReportMetricFunction = "count" | "sum" | "avg" | "min" | "max";
export type ReportFilterOperator =
  | "eq"
  | "ne"
  | "contains"
  | "gt"
  | "gte"
  | "lt"
  | "lte"
  | "isnull";

export interface ReportMetric {
  fn: ReportMetricFunction;
  field?: string;
  as: string;
}

export interface ReportFilter {
  field: string;
  op: ReportFilterOperator;
  value?: unknown;
  values?: unknown[];
  param?: string;
}

export interface ReportOrder {
  by: string;
  dir?: "asc" | "desc";
}

export interface ReportView {
  name: string;
  entity: string;
  title?: string;
  fields?: string[];
  groupBy?: string[];
  metrics?: ReportMetric[];
  filters?: ReportFilter[];
  orderBy?: ReportOrder[];
  limit?: number;
  engine?: "postgres" | "lakehouse";
}

/** A stable, manifest-safe identifier derived from a human title. */
export function slugifyReportName(value: string): string {
  const ascii = value
    .replace(/[đĐ]/g, (char) => (char === "đ" ? "d" : "D"))
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 64);
  if (!ascii) return "report";
  return /^[a-z]/.test(ascii) ? ascii : `report_${ascii}`;
}

export function humanizeReportName(name: string): string {
  const words = name.replace(/_/g, " ").trim();
  if (!words) return "Report";
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function uniqueReportName(
  desired: string,
  views: ReportView[],
  currentName?: string,
): string {
  const base = slugifyReportName(desired);
  const used = new Set(
    views
      .map((view) => view.name)
      .filter((name) => name && name !== currentName),
  );
  if (!used.has(base)) return base;
  let suffix = 2;
  while (used.has(`${base}_${suffix}`)) suffix += 1;
  return `${base}_${suffix}`;
}

export function entityLabel(entity: BizAppEntity | undefined): string {
  if (!entity) return "Data";
  return entity.label?.en || entity.label?.vi || humanizeReportName(entity.name);
}

export function fieldLabel(field: { name: string; label?: { en?: string | null; vi?: string | null } }): string {
  return field.label?.en || field.label?.vi || humanizeReportName(field.name);
}

export function defaultReportView(
  entities: BizAppEntity[],
  views: ReportView[],
): ReportView | undefined {
  const entity = entities[0];
  if (!entity) return undefined;
  const title = `${entityLabel(entity)} overview`;
  return {
    name: uniqueReportName(title, views),
    title,
    entity: entity.name,
    limit: 100,
  };
}

export function isSummaryView(view: ReportView): boolean {
  return Boolean(view.groupBy?.length || view.metrics?.length);
}

export function reportViewTitle(view: ReportView): string {
  return view.title?.trim() || humanizeReportName(view.name);
}

export function findReportView(
  manifest: BizAppManifest | undefined,
  viewName: string,
): ReportView | undefined {
  for (const block of manifest?.blocks || []) {
    if (block.type !== "report") continue;
    const view = reportViewsOf(block).find((candidate) => candidate.name === viewName);
    if (view) return view;
  }
  return undefined;
}

export function reportViewTitleFor(
  manifest: BizAppManifest | undefined,
  viewName: string,
): string {
  const view = findReportView(manifest, viewName);
  return view ? reportViewTitle(view) : humanizeReportName(viewName);
}

export function reportViewsOf(block: BizAppBlock | undefined): ReportView[] {
  const raw = block?.params?.views;
  if (!Array.isArray(raw)) return [];
  return raw.filter(
    (item): item is Record<string, unknown> =>
      Boolean(item) && typeof item === "object" && !Array.isArray(item),
  ) as unknown as ReportView[];
}

/**
 * Keep navigation in step with report blocks: every declared view becomes a
 * report tab, and tabs whose view was removed are dropped. Other views keep
 * their existing order.
 */
export function syncReportViews(manifest: BizAppManifest): BizAppManifest {
  const names = new Set<string>();
  (manifest.blocks || []).forEach((block) => {
    if (block.type !== "report") return;
    reportViewsOf(block).forEach((view) => {
      if (view.name) names.add(view.name);
    });
  });

  const existing = manifest.ui?.views ?? [];
  const kept = existing.filter(
    (view) => !view.startsWith("report:") || names.has(view.slice("report:".length)),
  );
  const added = [...names]
    .filter((name) => !kept.includes(`report:${name}`))
    .map((name) => `report:${name}`);

  return {
    ...manifest,
    ui: {
      ...manifest.ui,
      views: [...kept, ...added],
    },
  };
}
