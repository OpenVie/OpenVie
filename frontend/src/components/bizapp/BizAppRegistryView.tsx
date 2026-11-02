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

import { useCallback, useEffect, useState } from "react";
import { useFormatter, useLocale, useTranslations } from "next-intl";
import toast from "react-hot-toast";
import { Archive, Pencil, Plus, RefreshCw, Send } from "lucide-react";

import type { BizAppField, BizAppInstalledAppView, BizAppRecordPage, BizAppRecordView } from "@/types";
import {
  archiveBizAppRecordApi,
  bizAppApprovalBlock,
  listBizAppRecordsApi,
  memberCanWrite,
  submitBizAppRecordApi,
} from "@/lib/bizapp-api";
import { useApiClient } from "@/hooks/useApiClient";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ListPager } from "@/components/ui/list-pager";
import { Skeleton } from "@/components/ui/skeleton";
import { BizAppEntityFormDialog } from "@/components/bizapp/BizAppEntityFormDialog";

/**
 * The `registry:<entity>` native view: paged record table fed by the generic
 * record store, with create/edit/archive and (when the app declares an
 * `approval_workitem` block) a submit action. Write actions collapse to
 * member-visible state from the manifest RBAC.
 */
export function BizAppRegistryView({ app, entityName, openCreate = false }: {
  app: BizAppInstalledAppView;
  entityName: string;
  openCreate?: boolean;
}) {
  const t = useTranslations("BizApps");
  const locale = useLocale();
  const format = useFormatter();
  const { request } = useApiClient();
  const entity = app.manifest.entities.find((candidate) => candidate.name === entityName);
  const canWrite = memberCanWrite(app.manifest);
  const hasApproval = Boolean(bizAppApprovalBlock(app.manifest));

  const [page, setPage] = useState(0);
  const [data, setData] = useState<BizAppRecordPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [formTarget, setFormTarget] = useState<{ record: BizAppRecordView | null } | null>(
    openCreate ? { record: null } : null,
  );
  const [busyRecordId, setBusyRecordId] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!entity) return;
    setLoading(true);
    setError(null);
    try {
      setData(await listBizAppRecordsApi(request, app.kind, entityName, { page, size: 20 }));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("fallback.load"));
    } finally {
      setLoading(false);
    }
  }, [app.kind, entity, entityName, page, request, t]);

  useEffect(() => {
    const task = window.setTimeout(() => { void load(); }, 0);
    return () => window.clearTimeout(task);
  }, [load]);

  if (!entity) {
    return <p className="text-sm text-muted-foreground">{t("unknownEntity", { entity: entityName })}</p>;
  }

  const entityLabel = (entity.label ? (locale === "vi" ? entity.label.vi : entity.label.en) : null) ?? entity.name;

  const columns = entity.fields.slice(0, 4);
  const records = data?.content ?? [];

  const onSaved = async () => {
    setFormTarget(null);
    toast.success(t("record.saved"));
    await load();
  };

  const onArchive = async (record: BizAppRecordView) => {
    setBusyRecordId(record.id);
    try {
      await archiveBizAppRecordApi(request, app.kind, entityName, record.id);
      toast.success(t("record.archived"));
      await load();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("fallback.archive"));
    } finally {
      setBusyRecordId(null);
    }
  };

  const onSubmit = async (record: BizAppRecordView) => {
    setBusyRecordId(record.id);
    try {
      await submitBizAppRecordApi(request, app.kind, entityName, record.id);
      toast.success(t("record.submitted"));
      await load();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("fallback.submit"));
    } finally {
      setBusyRecordId(null);
    }
  };

  const approvalStatus = (record: BizAppRecordView): "APPROVED" | "REJECTED" | "PENDING" | null => {
    const value = (record.runtime ?? {})["approvalStatus"];
    return value === "APPROVED" || value === "REJECTED" || value === "PENDING" ? value : null;
  };

  // next-intl message keys are typed; the status set is closed so map it
  // explicitly instead of interpolating the key.
  const approvalKey: Record<"APPROVED" | "REJECTED" | "PENDING", "approval.approved" | "approval.rejected" | "approval.pending"> = {
    APPROVED: "approval.approved",
    REJECTED: "approval.rejected",
    PENDING: "approval.pending",
  };

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between space-y-0">
        <CardTitle>{entityLabel}</CardTitle>
        <div className="flex items-center gap-2">
          <Button size="icon" variant="ghost" onClick={() => { void load(); }} aria-label={t("refresh")}>
            <RefreshCw className="size-4" />
          </Button>
          {canWrite && (
            <Button size="sm" onClick={() => setFormTarget({ record: null })}>
              <Plus className="mr-2 size-4" />
              {t("record.create", { entity: entityLabel })}
            </Button>
          )}
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {loading ? (
          <div className="space-y-2">
            {[0, 1, 2].map((key) => <Skeleton key={key} className="h-10 w-full" />)}
          </div>
        ) : error ? (
          <div role="alert" className="rounded-lg border border-destructive/40 bg-destructive/10 p-4 text-sm">
            <p>{error}</p>
            <Button variant="outline" size="sm" className="mt-2" onClick={() => { void load(); }}>
              {t("retry")}
            </Button>
          </div>
        ) : records.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("record.empty", { entity: entityLabel })}</p>
        ) : (
          <div className="overflow-x-auto rounded-xl border">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b bg-muted/50 text-left text-muted-foreground">
                  {columns.map((field) => (
                    <th key={field.name} className="px-3 py-2 font-medium">
                      {(field.label ? (locale === "vi" ? field.label.vi : field.label.en) : null) ?? field.name}
                    </th>
                  ))}
                  <th className="px-3 py-2 font-medium">{t("record.updatedAt")}</th>
                  <th className="px-3 py-2 text-right font-medium">{t("record.actions")}</th>
                </tr>
              </thead>
              <tbody>
                {records.map((record) => {
                  const status = approvalStatus(record);
                  return (
                  <tr key={record.id} className="border-b last:border-0">
                    {columns.map((field) => (
                      <td key={field.name} className="max-w-48 truncate px-3 py-2">
                        {displayValue(field, record.payload[field.name])}
                      </td>
                    ))}
                    <td className="px-3 py-2 text-muted-foreground">
                      {format.dateTime(new Date(record.updatedAt), { dateStyle: "medium" })}
                    </td>
                    <td className="px-3 py-2">
                      <div className="flex items-center justify-end gap-1">
                        {status && (
                          <Badge
                            variant={
                              status === "APPROVED" ? "default"
                                : status === "REJECTED" ? "destructive" : "secondary"
                            }
                          >
                            {t(approvalKey[status])}
                          </Badge>
                        )}
                        {canWrite && (
                          <Button
                            size="icon" variant="ghost" className="size-8"
                            aria-label={t("record.edit")}
                            onClick={() => setFormTarget({ record })}
                          >
                            <Pencil className="size-4" />
                          </Button>
                        )}
                        {canWrite && hasApproval && status !== "APPROVED" && (
                          <Button
                            size="icon" variant="ghost" className="size-8"
                            aria-label={t("record.submit")}
                            disabled={busyRecordId === record.id || status === "PENDING"}
                            onClick={() => { void onSubmit(record); }}
                          >
                            <Send className="size-4" />
                          </Button>
                        )}
                        {canWrite && (
                          <Button
                            size="icon" variant="ghost" className="size-8"
                            aria-label={t("record.archive")}
                            disabled={busyRecordId === record.id}
                            onClick={() => { void onArchive(record); }}
                          >
                            <Archive className="size-4" />
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {data && data.totalPages > 1 && (
          <ListPager
            page={page + 1}
            pageCount={data.totalPages}
            size={data.size}
            total={data.totalElements}
            onPageChange={(value) => setPage(value - 1)}
            onSizeChange={() => undefined}
          />
        )}
      </CardContent>

      <BizAppEntityFormDialog
        app={app}
        entity={entity}
        record={formTarget?.record ?? null}
        open={formTarget !== null}
        onClose={() => setFormTarget(null)}
        onSaved={onSaved}
      />
    </Card>
  );
}

/** Human-readable cell value: booleans as check/cross, numbers localized. */
function displayValue(field: BizAppField, value: unknown): string {
  if (value === null || value === undefined || value === "") {
    return "—";
  }
  if (typeof value === "boolean") {
    return value ? "✓" : "✗";
  }
  if (field.type === "date" && typeof value === "string") {
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleDateString();
  }
  return String(value);
}
