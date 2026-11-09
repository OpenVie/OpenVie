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
import { useFormatter, useTranslations } from "next-intl";

import type { BizAppInstalledAppView, BizAppRecordView } from "@/types";
import { useApiClient } from "@/hooks/useApiClient";
import { listBizAppRecordsApi } from "@/lib/bizapp-api";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * The `timeline:<entity>` native view: the most recent records rendered as a
 * chronological card feed — a read-only alternative to the registry table.
 */
export function BizAppTimelineView({ app, entityName }: {
  app: BizAppInstalledAppView;
  entityName: string;
}) {
  const t = useTranslations("BizApps");
  const format = useFormatter();
  const { request } = useApiClient();
  const [records, setRecords] = useState<BizAppRecordView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const page = await listBizAppRecordsApi(request, app.kind, entityName, { page: 0, size: 10 });
      setRecords(page.content);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("fallback.load"));
    } finally {
      setLoading(false);
    }
  }, [app.kind, entityName, request, t]);

  useEffect(() => {
    const task = window.setTimeout(() => { void load(); }, 0);
    return () => window.clearTimeout(task);
  }, [load]);

  const summary = (record: BizAppRecordView): string[] =>
    Object.entries(record.payload)
      .filter(([, value]) => value !== null && value !== undefined && value !== "")
      .slice(0, 3)
      .map(([key, value]) => `${key}: ${String(value)}`);

  const approvalKey: Record<string, "approval.approved" | "approval.rejected" | "approval.pending"> = {
    APPROVED: "approval.approved",
    REJECTED: "approval.rejected",
    PENDING: "approval.pending",
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("timeline.title")}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {loading ? (
          <div className="space-y-2">
            {[0, 1, 2].map((key) => <Skeleton key={key} className="h-12 w-full" />)}
          </div>
        ) : error ? (
          <div role="alert" className="rounded-lg border border-destructive/40 bg-destructive/10 p-4 text-sm">
            <p>{error}</p>
          </div>
        ) : records.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("timeline.empty")}</p>
        ) : (
          records.map((record) => {
            const runtime = record.runtime ?? {};
            const status = runtime["approvalStatus"];
            return (
              <div key={record.id} className="rounded-xl border p-4">
                <div className="flex items-center justify-between gap-3">
                  <p className="text-xs text-muted-foreground">
                    {format.dateTime(new Date(record.createdAt), { dateStyle: "medium", timeStyle: "short" })}
                  </p>
                  {status === "APPROVED" || status === "REJECTED" || status === "PENDING" ? (
                    <Badge
                      variant={status === "APPROVED" ? "default" : status === "REJECTED" ? "destructive" : "secondary"}
                    >
                      {t(approvalKey[status])}
                    </Badge>
                  ) : undefined}
                </div>
                <ul className="mt-2 space-y-1 text-sm">
                  {summary(record).map((line, index) => (
                    <li key={index} className="truncate">{line}</li>
                  ))}
                </ul>
              </div>
            );
          })
        )}
      </CardContent>
    </Card>
  );
}
