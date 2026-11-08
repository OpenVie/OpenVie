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

"use client";

import { useState } from "react";
import { BarChart3, Plus, Trash2 } from "lucide-react";
import type { BizAppEntity, BizAppField } from "@/types";
import { Button } from "@/components/ui/button";
import {
  defaultReportView,
  entityLabel,
  fieldLabel,
  humanizeReportName,
  isSummaryView,
  reportViewTitle,
  uniqueReportName,
  type ReportFilter,
  type ReportFilterOperator,
  type ReportMetric,
  type ReportMetricFunction,
  type ReportOrder,
  type ReportView,
} from "@/lib/bizapp-report";

const METRIC_OPTIONS: Array<{ value: ReportMetricFunction; label: string }> = [
  { value: "count", label: "Count rows" },
  { value: "sum", label: "Sum" },
  { value: "avg", label: "Average" },
  { value: "min", label: "Minimum" },
  { value: "max", label: "Maximum" },
];

const FILTER_OPTIONS: Array<{ value: ReportFilterOperator; label: string }> = [
  { value: "eq", label: "is" },
  { value: "ne", label: "is not" },
  { value: "contains", label: "contains" },
  { value: "gt", label: "greater than" },
  { value: "gte", label: "at least" },
  { value: "lt", label: "less than" },
  { value: "lte", label: "at most" },
  { value: "isnull", label: "is empty" },
];

interface ReportBuilderProps {
  views: ReportView[];
  entities: BizAppEntity[];
  onChange: (views: ReportView[]) => void;
}

/**
 * Friendly report configuration: a non-technical user picks an entity, a
 * display style, fields and optional filters. The stored shape stays the
 * declarative manifest view contract; no SQL or raw JSON reaches the UI.
 */
export function ReportBuilder({ views, entities, onChange }: ReportBuilderProps) {
  const [selected, setSelected] = useState(0);

  const addView = () => {
    const next = defaultReportView(entities, views);
    if (!next) return;
    onChange([...views, next]);
    setSelected(views.length);
  };

  if (views.length === 0) {
    return (
      <div className="rounded-lg border bg-muted/20 p-3 text-center space-y-2">
        <BarChart3 className="mx-auto h-5 w-5 text-muted-foreground" />
        <p className="font-medium text-muted-foreground">No report configured yet</p>
        <p className="text-[11px] text-muted-foreground">
          {entities.length === 0
            ? "Create an entity first, then add a report."
            : "Turn your records into a simple table or summary."}
        </p>
        <Button type="button" size="sm" onClick={addView} disabled={entities.length === 0}>
          <Plus className="h-3.5 w-3.5" /> Create report
        </Button>
      </div>
    );
  }

  const index = Math.min(selected, views.length - 1);
  const view = views[index];
  const entity =
    entities.find((candidate) => candidate.name === view.entity) || entities[0];
  const fields = entity?.fields || [];
  const summary = isSummaryView(view);

  const patch = (next: Partial<ReportView>) => {
    onChange(views.map((candidate, i) => (i === index ? { ...candidate, ...next } : candidate)));
  };

  const title = view.title ?? humanizeReportName(view.name);

  const changeTitle = (nextTitle: string) => {
    patch({
      title: nextTitle,
      name: uniqueReportName(nextTitle || "report", views, view.name),
    });
  };

  const changeEntity = (entityName: string) => {
    const nextEntity = entities.find((candidate) => candidate.name === entityName);
    patch({
      entity: entityName,
      title: view.title || `${entityLabel(nextEntity)} overview`,
      groupBy: [],
      metrics: [],
      filters: [],
      orderBy: [],
    });
  };

  const changeMode = (mode: "list" | "summary") => {
    if (mode === "list") {
      patch({
        groupBy: undefined,
        metrics: undefined,
        orderBy: view.orderBy?.filter((order) => fields.some((field) => field.name === order.by)),
      });
      return;
    }
    patch({
      groupBy: view.groupBy?.length ? view.groupBy : [fields[0]?.name].filter(Boolean),
      metrics: view.metrics?.length ? view.metrics : [newMetric("count", undefined, views[index]?.metrics || [])],
      orderBy: [],
    });
  };

  const updateMetric = (metricIndex: number, next: Partial<ReportMetric>) => {
    const metrics = [...(view.metrics || [])];
    const current = metrics[metricIndex] || { fn: "count", as: "count" };
    const merged: ReportMetric = { ...current, ...next };
    if (merged.fn === "count") {
      merged.field = undefined;
    } else if (!merged.field) {
      merged.field = metricFields(merged.fn, fields)[0]?.name;
    }
    merged.as = uniqueMetricAlias(merged, metrics, metricIndex);
    metrics[metricIndex] = merged;
    patch({ metrics });
  };

  const addMetric = () => {
    const metrics = [...(view.metrics || [])];
    metrics.push(newMetric("count", undefined, metrics));
    patch({ metrics });
  };

  const removeMetric = (metricIndex: number) => {
    patch({ metrics: (view.metrics || []).filter((_, i) => i !== metricIndex) });
  };

  const addFilter = () => {
    const first = fields[0];
    if (!first) return;
    patch({
      filters: [...(view.filters || []), { field: first.name, op: "eq", value: "" }],
    });
  };

  const updateFilter = (filterIndex: number, next: Partial<ReportFilter>) => {
    const filters = [...(view.filters || [])];
    const merged: ReportFilter = { ...filters[filterIndex], ...next };
    if (merged.op === "isnull") {
      delete merged.value;
      delete merged.values;
    }
    filters[filterIndex] = merged;
    patch({ filters });
  };

  const removeFilter = (filterIndex: number) => {
    patch({ filters: (view.filters || []).filter((_, i) => i !== filterIndex) });
  };

  const sortOptions = summary
    ? [
        ...(view.groupBy || []).map((name) => ({
          value: name,
          label: fieldLabelOf(fields, name),
        })),
        ...(view.metrics || []).map((metric) => ({
          value: metric.as,
          label: humanizeReportName(metric.as),
        })),
      ]
    : fields.map((field) => ({ value: field.name, label: fieldLabel(field) }));

  const addSort = () => {
    const first = sortOptions[0];
    if (!first) return;
    patch({
      orderBy: [...(view.orderBy || []), { by: first.value, dir: "asc" }],
    });
  };

  const updateSort = (sortIndex: number, next: Partial<ReportOrder>) => {
    const orderBy = [...(view.orderBy || [])];
    orderBy[sortIndex] = { ...orderBy[sortIndex], ...next };
    patch({ orderBy });
  };

  const removeSort = (sortIndex: number) => {
    patch({ orderBy: (view.orderBy || []).filter((_, i) => i !== sortIndex) });
  };

  return (
    <div className="space-y-3 border-t pt-3">
      <div className="flex min-w-0 gap-1.5">
        <select
          value={index}
          onChange={(event) => setSelected(Number(event.target.value))}
          className="min-w-0 flex-1 border rounded px-2 py-1 bg-background text-xs"
          aria-label="Choose report"
        >
          {views.map((candidate, i) => (
            <option key={`${candidate.name}-${i}`} value={i}>
              {reportViewTitle(candidate)}
            </option>
          ))}
        </select>
        <Button type="button" variant="outline" size="icon-sm" onClick={addView} disabled={!entities.length} title="Add report">
          <Plus className="h-3.5 w-3.5" />
        </Button>
        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          onClick={() => onChange(views.filter((_, i) => i !== index))}
          title="Delete report"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </Button>
      </div>

      <div>
        <label className="font-medium text-muted-foreground">Report title</label>
        <input
          type="text"
          value={title}
          onChange={(event) => changeTitle(event.target.value)}
          className="w-full mt-1 border rounded px-2 py-1 bg-background"
        />
      </div>

      <div>
        <label className="font-medium text-muted-foreground">Data source</label>
        <select
          value={entity?.name || ""}
          onChange={(event) => changeEntity(event.target.value)}
          className="w-full mt-1 border rounded px-2 py-1 bg-background text-xs"
        >
          {entities.map((candidate) => (
            <option key={candidate.name} value={candidate.name}>
              {entityLabel(candidate)}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label className="font-medium text-muted-foreground">Show as</label>
        <select
          value={summary ? "summary" : "list"}
          onChange={(event) => changeMode(event.target.value as "list" | "summary")}
          className="w-full mt-1 border rounded px-2 py-1 bg-background text-xs"
        >
          <option value="list">List of records</option>
          <option value="summary">Grouped summary</option>
        </select>
      </div>

      {!summary && (
        <div className="space-y-1.5">
          <label className="font-medium text-muted-foreground">Columns</label>
          <div className="grid grid-cols-2 gap-1">
            {fields.map((field) => {
              const selectedFields = view.fields?.length
                ? view.fields
                : fields.map((candidate) => candidate.name);
              const checked = selectedFields.includes(field.name);
              return (
                <label key={field.name} className="flex items-center gap-1.5 text-[10px]">
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={() => {
                      const next = checked
                        ? selectedFields.filter((name) => name !== field.name)
                        : [...selectedFields, field.name];
                      if (next.length === 0) return;
                      patch({
                        fields: next.length === fields.length ? undefined : next,
                      });
                    }}
                  />
                  <span className="truncate">{fieldLabel(field)}</span>
                </label>
              );
            })}
          </div>
        </div>
      )}

      {summary && (
        <>
          <div>
            <label className="font-medium text-muted-foreground">Group by</label>
            <select
              value={view.groupBy?.[0] || ""}
              onChange={(event) =>
                patch({ groupBy: event.target.value ? [event.target.value] : [] })
              }
              className="w-full mt-1 border rounded px-2 py-1 bg-background text-xs"
            >
              <option value="">Choose a field</option>
              {fields.map((field) => (
                <option key={field.name} value={field.name}>
                  {fieldLabel(field)}
                </option>
              ))}
            </select>
          </div>

          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <label className="font-medium text-muted-foreground">Summary values</label>
              <Button type="button" variant="ghost" size="icon-sm" onClick={addMetric} title="Add summary value">
                <Plus className="h-3.5 w-3.5" />
              </Button>
            </div>
            {(view.metrics || []).map((metric, metricIndex) => (
              <div key={`${metric.as}-${metricIndex}`} className="rounded border bg-muted/10 p-2 space-y-1.5">
                <div className="flex min-w-0 gap-1.5">
                  <select
                    value={metric.fn}
                    onChange={(event) =>
                      updateMetric(metricIndex, {
                        fn: event.target.value as ReportMetricFunction,
                        field: undefined,
                      })
                    }
                    className="min-w-0 flex-1 border rounded px-1.5 py-0.5 bg-background text-[11px]"
                  >
                    {METRIC_OPTIONS.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                  <button
                    type="button"
                    onClick={() => removeMetric(metricIndex)}
                    className="shrink-0 text-muted-foreground hover:text-destructive"
                    title="Remove summary value"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                </div>
                {metric.fn !== "count" && (
                  <select
                    value={metric.field || ""}
                    onChange={(event) => updateMetric(metricIndex, { field: event.target.value })}
                    className="w-full border rounded px-1.5 py-0.5 bg-background text-[11px]"
                  >
                    {metricFields(metric.fn, fields).map((field) => (
                      <option key={field.name} value={field.name}>
                        {fieldLabel(field)}
                      </option>
                    ))}
                  </select>
                )}
              </div>
            ))}
          </div>
        </>
      )}

      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <label className="font-medium text-muted-foreground">Filters</label>
          <Button type="button" variant="ghost" size="icon-sm" onClick={addFilter} disabled={!fields.length} title="Add filter">
            <Plus className="h-3.5 w-3.5" />
          </Button>
        </div>
        {(view.filters || []).map((filter, filterIndex) => {
          const field = fields.find((candidate) => candidate.name === filter.field);
          return (
            <div key={`${filter.field}-${filterIndex}`} className="rounded border bg-muted/10 p-2 space-y-1.5">
              <div className="flex min-w-0 gap-1.5">
                <select
                  value={filter.field}
                  onChange={(event) =>
                    updateFilter(filterIndex, { field: event.target.value, value: "", values: undefined })
                  }
                  className="min-w-0 flex-1 border rounded px-1.5 py-0.5 bg-background text-[11px]"
                >
                  {fields.map((candidate) => (
                    <option key={candidate.name} value={candidate.name}>
                      {fieldLabel(candidate)}
                    </option>
                  ))}
                </select>
                <select
                  value={filter.op}
                  onChange={(event) =>
                    updateFilter(filterIndex, { op: event.target.value as ReportFilterOperator })
                  }
                  className="w-24 shrink-0 border rounded px-1.5 py-0.5 bg-background text-[11px]"
                >
                  {FILTER_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  onClick={() => removeFilter(filterIndex)}
                  className="shrink-0 text-muted-foreground hover:text-destructive"
                  title="Remove filter"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </div>
              {filter.op !== "isnull" && (
                <FilterValueInput
                  field={field}
                  value={filter.value}
                  onChange={(value) => updateFilter(filterIndex, { value })}
                />
              )}
            </div>
          );
        })}
      </div>

      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <label className="font-medium text-muted-foreground">Sort</label>
          <Button type="button" variant="ghost" size="icon-sm" onClick={addSort} disabled={!sortOptions.length} title="Add sort">
            <Plus className="h-3.5 w-3.5" />
          </Button>
        </div>
        {(view.orderBy || []).map((order, sortIndex) => (
          <div key={`${order.by}-${sortIndex}`} className="flex min-w-0 gap-1.5">
            <select
              value={order.by}
              onChange={(event) => updateSort(sortIndex, { by: event.target.value })}
              className="min-w-0 flex-1 border rounded px-1.5 py-0.5 bg-background text-[11px]"
            >
              {sortOptions.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            <select
              value={order.dir || "asc"}
              onChange={(event) => updateSort(sortIndex, { dir: event.target.value as "asc" | "desc" })}
              className="w-20 shrink-0 border rounded px-1.5 py-0.5 bg-background text-[11px]"
            >
              <option value="asc">A → Z</option>
              <option value="desc">Z → A</option>
            </select>
            <button
              type="button"
              onClick={() => removeSort(sortIndex)}
              className="shrink-0 text-muted-foreground hover:text-destructive"
              title="Remove sort"
            >
              <Trash2 className="h-3.5 w-3.5" />
            </button>
          </div>
        ))}
      </div>

      <div>
        <label className="font-medium text-muted-foreground">Maximum rows</label>
        <input
          type="number"
          min={1}
          max={500}
          value={view.limit || 100}
          onChange={(event) => patch({ limit: Math.min(500, Math.max(1, Number(event.target.value) || 100)) })}
          className="w-full mt-1 border rounded px-2 py-1 bg-background"
        />
      </div>

      <div className="rounded border bg-muted/20 px-2 py-1.5 text-[11px] text-muted-foreground">
        {summary
          ? `Summary of ${entityLabel(entity)}${view.groupBy?.[0] ? ` by ${fieldLabelOf(fields, view.groupBy[0])}` : ""}.`
          : `Table of ${entityLabel(entity)} records${view.fields?.length ? ` with ${view.fields.length} selected column(s)` : ""}.`}{" "}
        This report will appear as a tab in the app.
      </div>

      <details className="text-[10px] text-muted-foreground">
        <summary className="cursor-pointer">Advanced</summary>
        <p className="mt-1 break-all font-mono">Internal view name: {view.name}</p>
      </details>
    </div>
  );
}

function FilterValueInput({
  field,
  value,
  onChange,
}: {
  field: BizAppField | undefined;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  if (field?.type === "boolean") {
    return (
      <select
        value={String(value ?? "")}
        onChange={(event) => onChange(event.target.value === "" ? "" : event.target.value === "true")}
        className="w-full border rounded px-1.5 py-0.5 bg-background text-[11px]"
      >
        <option value="">Choose a value</option>
        <option value="true">Yes</option>
        <option value="false">No</option>
      </select>
    );
  }
  if (field?.type === "enum") {
    return (
      <select
        value={String(value ?? "")}
        onChange={(event) => onChange(event.target.value)}
        className="w-full border rounded px-1.5 py-0.5 bg-background text-[11px]"
      >
        <option value="">Choose a value</option>
        {(field.options || []).map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    );
  }
  return (
    <input
      type={field?.type === "number" ? "number" : "text"}
      value={value == null ? "" : String(value)}
      onChange={(event) =>
        onChange(
          field?.type === "number" && event.target.value !== ""
            ? Number(event.target.value)
            : event.target.value,
        )
      }
      placeholder="Value"
      className="w-full border rounded px-1.5 py-0.5 bg-background text-[11px]"
    />
  );
}

function metricFields(fn: ReportMetricFunction, fields: BizAppField[]): BizAppField[] {
  if (fn === "sum" || fn === "avg") {
    return fields.filter((field) => field.type === "number");
  }
  return fields;
}

function newMetric(
  fn: ReportMetricFunction,
  field: string | undefined,
  metrics: ReportMetric[],
): ReportMetric {
  const candidate: ReportMetric = { fn, field, as: "" };
  return { ...candidate, as: uniqueMetricAlias(candidate, metrics) };
}

function uniqueMetricAlias(
  metric: ReportMetric,
  metrics: ReportMetric[],
  currentIndex?: number,
): string {
  const base =
    metric.fn === "count"
      ? "count"
      : `${metric.fn}_${metric.field || "value"}`;
  const used = new Set(
    metrics
      .filter((_, index) => index !== currentIndex)
      .map((candidate) => candidate.as)
      .filter(Boolean),
  );
  let alias = base.replace(/[^a-z0-9_]/gi, "_").toLowerCase();
  if (!/^[a-z]/.test(alias)) alias = `value_${alias}`;
  if (!used.has(alias)) return alias;
  let suffix = 2;
  while (used.has(`${alias}_${suffix}`)) suffix += 1;
  return `${alias}_${suffix}`;
}

function fieldLabelOf(fields: BizAppField[], name: string): string {
  const field = fields.find((candidate) => candidate.name === name);
  return field ? fieldLabel(field) : humanizeReportName(name);
}
