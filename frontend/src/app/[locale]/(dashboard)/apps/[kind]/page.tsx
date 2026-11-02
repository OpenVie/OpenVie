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
import { useParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";

import type { BizAppInstalledAppView } from "@/types";
import { useApiClient } from "@/hooks/useApiClient";
import { getInstalledBizAppApi } from "@/lib/bizapp-api";
import { useAuthStore } from "@/components/providers/StoreProvider";
import { BizAppRenderer } from "@/components/bizapp/BizAppRenderer";
import { Button } from "@/components/ui/button";

/**
 * One installed app: resolves the manifest that was frozen at install time
 * (falling back to the platform template manifest for display-only shape) and
 * hands it to the manifest-driven renderer.
 */
export default function BizAppDetailPage() {
  const t = useTranslations("BizApps");
  const params = useParams<{ kind: string }>();
  const kind = params?.kind ?? "";
  const { request } = useApiClient();
  const user = useAuthStore((s) => s.user);
  const workspaceAdmin = user?.workspaceRole === "WORKSPACE_ADMIN";

  const [app, setApp] = useState<BizAppInstalledAppView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!kind) return;
    setLoading(true);
    setError(null);
    try {
      setApp(await getInstalledBizAppApi(request, kind));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("fallback.load"));
    } finally {
      setLoading(false);
    }
  }, [kind, request, t]);

  useEffect(() => {
    const task = window.setTimeout(() => { void load(); }, 0);
    return () => window.clearTimeout(task);
  }, [load]);

  const label = app ? (app.nameVi ? app.nameVi : app.nameEn) : kind;

  return (
    <div className="mx-auto w-full max-w-5xl space-y-6 px-4 py-8">
      <div className="flex items-center gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{label}</h1>
          <p className="mt-1 text-xs text-muted-foreground">
            {app ? t("appCard", { kind, version: app.version }) : kind}
          </p>
        </div>
      </div>

      {loading ? (
        <div className="flex items-center gap-2 py-12 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" /> {t("loading")}
        </div>
      ) : error ? (
        <div role="alert" className="rounded-lg border border-destructive/40 bg-destructive/10 p-4 text-sm">
          <p>{error}</p>
          <Button variant="outline" size="sm" className="mt-2" onClick={() => { void load(); }}>
            {t("retry")}
          </Button>
        </div>
      ) : app ? (
        <BizAppRenderer app={app} admin={workspaceAdmin} />
      ) : null}
    </div>
  );
}
