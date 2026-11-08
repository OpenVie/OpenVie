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
import { useLocale, useTranslations } from "next-intl";
import toast from "react-hot-toast";
import { AlertCircle, Download, Loader2, RefreshCw } from "lucide-react";

import type { BizAppInstalledAppView } from "@/types";
import { useApiClient } from "@/hooks/useApiClient";
import { exportBizAppReportApi, runBizAppReportApi } from "@/lib/bizapp-api";
import { reportViewTitleFor } from "@/lib/bizapp-report";
import { downloadBlob } from "@/lib/download";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

/**
 * The `report:<view>` native view: rows of one manifest-declared SELECT,
 * executed backend-side through the `:tenant`/`:app` gated query. Admin-only
 * on the API too; members see an explanatory empty state instead of a 403.
 */
export function BizAppReportView({ app, viewName, admin }: {
  app: BizAppInstalledAppView;
  viewName: string;
  admin: boolean;
}) {
  const t = useTranslations("BizApps");
  const locale = useLocale();
  const { request } = useApiClient();
  const [rows, setRows] = useState<Array<Record<string, unknown>>>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setRows(await runBizAppReportApi(request, app.kind, viewName, {}));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("fallback.load"));
    } finally {
      setLoading(false);
    }
  }, [app.kind, viewName, request, t]);

  useEffect(() => {
    const task = window.setTimeout(() => { void load(); }, 0);
    return () => window.clearTimeout(task);
  }, [load]);

  const columns = rows.length === 0 ? [] : Object.keys(rows[0]).slice(0, 6);

  // The file always carries every column and row the view returns, not the
  // six columns the runtime table shows.
  const onExport = async (format: "csv" | "xlsx") => {
    setExporting(true);
    try {
      const file = await exportBizAppReportApi(request, app.kind, viewName, format, locale);
      downloadBlob(file.blob, file.fileName);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("fallback.export"));
    } finally {
      setExporting(false);
    }
  };

  const exportMenu = (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button size="sm" variant="outline" disabled={exporting} aria-label={t("export.button")} />
        }
      >
        {exporting
          ? <Loader2 className="mr-1.5 size-4 animate-spin" aria-hidden="true" />
          : <Download className="mr-1.5 size-4" aria-hidden="true" />}
        {t("export.button")}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem onClick={() => void onExport("csv")} onSelect={() => void onExport("csv")}>
          {t("export.csv")}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => void onExport("xlsx")} onSelect={() => void onExport("xlsx")}>
          {t("export.xlsx")}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );

  if (!admin) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{reportViewTitleFor(app.manifest, viewName)}</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">{t("report.adminOnly")}</p>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between space-y-0">
        <CardTitle>{reportViewTitleFor(app.manifest, viewName)}</CardTitle>
        <div className="flex items-center gap-2">
          <Button size="icon" variant="ghost" onClick={() => { void load(); }} aria-label={t("refresh")}>
            {loading ? <Loader2 className="size-4 animate-spin" /> : <RefreshCw className="size-4" />}
          </Button>
          {exportMenu}
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {!loading && error && (
          <div role="alert" className="flex items-start gap-2 rounded-lg border border-destructive/40 bg-destructive/10 p-4 text-sm">
            <AlertCircle className="mt-0.5 size-4 text-destructive" />
            <p>{error}</p>
          </div>
        )}
        {loading ? (
          <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" /> {t("loading")}
          </div>
        ) : rows.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("report.empty")}</p>
        ) : (
          <div className="overflow-x-auto rounded-xl border">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b bg-muted/50 text-left text-muted-foreground">
                  {columns.map((column) => (
                    <th key={column} className="px-3 py-2 font-medium">{column}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((row, index) => (
                  <tr key={index} className="border-b last:border-0">
                    {columns.map((column) => (
                      <td key={column} className="max-w-56 truncate px-3 py-2">
                        {row[column] === null || row[column] === undefined ? "—" : String(row[column])}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
