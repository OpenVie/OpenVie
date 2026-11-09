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

import { use, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import toast from "react-hot-toast";
import { ArrowLeft, Loader2 } from "lucide-react";
import { useRouter } from "@/i18n/navigation";
import type { BizAppManifest } from "@/types";
import { useApiClient } from "@/hooks/useApiClient";
import { Button } from "@/components/ui/button";
import { ComposerCanvas } from "@/components/bizapp/composer/ComposerCanvas";
import {
  getBizAppDraftApi,
  installBizAppApi,
  listInstalledBizAppsApi,
  saveBizAppDraftApi,
  upgradeBizAppApi,
} from "@/lib/bizapp-api";
import { upgradeManifestVersion } from "@/lib/bizapp-upgrade";

export default function ComposeDraftPage({
  params,
}: {
  params: Promise<{ draftId: string }>;
}) {
  const { draftId } = use(params);
  const t = useTranslations("BizApps");
  const router = useRouter();
  const { request } = useApiClient();

  const [manifest, setManifest] = useState<BizAppManifest | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    const fetchDraft = async () => {
      try {
        const draft = await getBizAppDraftApi(request, draftId);
        if (!active) return;
        if (draft.manifest) {
          setManifest(draft.manifest as unknown as BizAppManifest);
        } else {
          setManifest(null);
          setError(t("composer.draftNotFound"));
        }
      } catch (err) {
        if (!active) return;
        setManifest(null);
        setError(err instanceof Error ? err.message : t("composer.loadFailed"));
      } finally {
        if (active) setLoading(false);
      }
    };
    void fetchDraft();
    return () => {
      active = false;
    };
  }, [draftId, request, t]);

  const handleSaveDraft = async (updated: BizAppManifest) => {
    try {
      await saveBizAppDraftApi(request, draftId, {
        id: draftId,
        title: updated.name?.en || updated.kind,
        source: "canvas",
        manifest: updated,
      });
      toast.success(t("composer.saved"));
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("composer.saveFailed"));
    }
  };

  const handleInstall = async (updated: BizAppManifest) => {
    try {
      const installed = await listInstalledBizAppsApi(request);
      const active = installed.find((app) => app.kind === updated.kind && app.status === "ACTIVE");
      if (active) {
        const next = upgradeManifestVersion(updated, active.version);
        await upgradeBizAppApi(request, updated.kind, next);
        await saveBizAppDraftApi(request, draftId, {
          id: draftId,
          title: next.name?.en || next.kind,
          source: "canvas",
          manifest: next,
        });
        toast.success(t("composer.upgraded"));
      } else {
        await installBizAppApi(request, { manifest: updated });
        toast.success(t("composer.installed"));
      }
      router.push(`/apps/${updated.kind}`);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("composer.installFailed"));
    }
  };

  if (loading) {
    return (
      <div className="h-screen w-screen flex items-center justify-center bg-background">
        <Loader2 className="h-6 w-6 animate-spin text-primary" />
      </div>
    );
  }

  if (error || !manifest) {
    return (
      <div className="h-screen w-screen flex flex-col items-center justify-center gap-4 p-8 text-center bg-background">
        <p className="text-destructive font-medium">{error || t("composer.draftNotFound")}</p>
        <Button variant="outline" size="sm" onClick={() => router.push("/admin/apps")}>
          <ArrowLeft className="mr-1.5 size-4" />
          {t("composer.back")}
        </Button>
      </div>
    );
  }

  return (
    <ComposerCanvas
      initialManifest={manifest}
      draftId={draftId}
      onSaveDraft={handleSaveDraft}
      onInstall={handleInstall}
      onBack={() => router.push("/admin/apps")}
    />
  );
}
