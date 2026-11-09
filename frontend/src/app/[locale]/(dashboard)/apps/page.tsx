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
import { useTranslations } from "next-intl";
import { Loader2, RefreshCw } from "lucide-react";

import type { BizAppInstalledAppView } from "@/types";
import { useApiClient } from "@/hooks/useApiClient";
import { listInstalledBizAppsApi } from "@/lib/bizapp-api";
import { useRouter } from "@/i18n/navigation";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * Launchpad of the installed Tier-3 apps: one card per installed app, deep
 * linking into its manifest-rendered surface.
 */
export default function AppsPage() {
  const t = useTranslations("BizApps");
  const { request } = useApiClient();
  const router = useRouter();
  const [apps, setApps] = useState<BizAppInstalledAppView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setApps(await listInstalledBizAppsApi(request));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("fallback.load"));
    } finally {
      setLoading(false);
    }
  }, [request, t]);

  useEffect(() => {
    const task = window.setTimeout(() => { void load(); }, 0);
    return () => window.clearTimeout(task);
  }, [load]);

  const iconFor = (kind: string, icon?: string | null): string => {
    if (icon === "briefcase" || (icon === null && kind === "crm")) return "💼";
    if (icon === "calendar" || (icon === null && kind === "booking")) return "📅";
    return "🧩";
  };

  const appLabel = (app: BizAppInstalledAppView): string =>
    app.nameVi ? app.nameVi : app.nameEn;

  return (
    <div className="mx-auto w-full max-w-5xl space-y-6 px-4 py-8">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{t("title")}</h1>
          <p className="mt-1 text-sm text-muted-foreground">{t("subtitle")}</p>
        </div>
        <Button size="icon" variant="ghost" onClick={() => { void load(); }} aria-label={t("refresh")}>
          {loading ? <Loader2 className="size-4 animate-spin" /> : <RefreshCw className="size-4" />}
        </Button>
      </div>

      {loading ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {[0, 1, 2].map((key) => <Skeleton key={key} className="h-28 w-full" />)}
        </div>
      ) : error ? (
        <div role="alert" className="rounded-lg border border-destructive/40 bg-destructive/10 p-4 text-sm">
          <p>{error}</p>
          <Button variant="outline" size="sm" className="mt-2" onClick={() => { void load(); }}>
            {t("retry")}
          </Button>
        </div>
      ) : apps.length === 0 ? (
        <Card>
          <CardContent className="p-8 text-center">
            <p className="text-sm text-muted-foreground">{t("empty")}</p>
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {apps.map((app) => (
            <button
              key={app.kind}
              className="text-left"
              onClick={() => router.push(`/apps/${app.kind}`)}
            >
              <Card className="h-full transition-colors hover:border-primary">
                <CardContent className="flex items-start gap-3 p-5">
                  <span aria-hidden className="text-3xl">{iconFor(app.kind, app.icon)}</span>
                  <div className="min-w-0">
                    <p className="truncate font-medium">{appLabel(app)}</p>
                    <p className="mt-1 truncate text-xs text-muted-foreground">
                      {t("appCard", { kind: app.kind, version: app.version })}
                    </p>
                    {(app.manifest.entities.length > 0) && (
                      <p className="mt-2 text-xs text-muted-foreground">
                        {app.manifest.entities.length} {t("entitiesSuffix")}
                      </p>
                    )}
                  </div>
                </CardContent>
              </Card>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
