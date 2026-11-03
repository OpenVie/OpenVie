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

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import toast from "react-hot-toast";
import { Loader2 } from "lucide-react";
import { useRouter } from "@/i18n/navigation";
import type { BizAppManifest } from "@/types";
import { useApiClient } from "@/hooks/useApiClient";
import { getApiBase } from "@/lib/auth-api";
import { ComposerCanvas } from "@/components/bizapp/composer/ComposerCanvas";
import { installBizAppApi, saveBizAppDraftApi } from "@/lib/bizapp-api";

const DEFAULT_MANIFEST: BizAppManifest = {
  schema: "bizapp.v1",
  kind: "custom-app",
  name: { en: "Custom App", vi: "Ứng dụng tùy chỉnh" },
  version: 1,
  icon: "box",
  storage: "jsonb",
  rbac: {
    read: ["ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER"],
    write: ["WORKSPACE_ADMIN", "MEMBER"],
  },
  entities: [
    {
      name: "records",
      label: { en: "Records", vi: "Bản ghi" },
      fields: [
        { name: "title", type: "string", required: true },
        { name: "status", type: "string" },
      ],
    },
  ],
  blocks: [],
  ui: {
    views: ["registry:records", "form:records"],
  },
};

export default function ComposeNewPage() {
  const t = useTranslations("BizApps");
  const router = useRouter();
  const { request } = useApiClient();

  const [manifest, setManifest] = useState<BizAppManifest | null>(null);

  useEffect(() => {
    let active = true;
    const load = async () => {
      // "New from template": admins land here with ?template=<ref> to start
      // the canvas from a platform or organization template's manifest.
      const ref = new URLSearchParams(window.location.search).get("template");
      if (!ref) {
        setManifest(DEFAULT_MANIFEST);
        return;
      }
      try {
        const res = await request(
          `${getApiBase()}/marketplace/bizapps/${encodeURIComponent(ref)}`,
        );
        if (!active) return;
        if (res.ok) {
          setManifest((await res.json()) as BizAppManifest);
        } else {
          toast.error(t("composer.templateNotFound"));
          setManifest(DEFAULT_MANIFEST);
        }
      } catch (err) {
        if (!active) return;
        toast.error(err instanceof Error ? err.message : t("composer.templateNotFound"));
        setManifest(DEFAULT_MANIFEST);
      }
    };
    void load();
    return () => {
      active = false;
    };
  }, [request, t]);

  const handleSaveDraft = async (updated: BizAppManifest) => {
    try {
      const draft = await saveBizAppDraftApi(request, "new", {
        title: updated.name?.en || updated.kind,
        source: "canvas",
        manifest: updated,
      });
      toast.success(t("composer.saved"));
      router.replace(`/admin/apps/compose/${draft.id}`);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("composer.saveFailed"));
    }
  };

  const handleInstall = async (updated: BizAppManifest) => {
    try {
      await installBizAppApi(request, { manifest: updated });
      toast.success(t("composer.installed"));
      router.push(`/apps/${updated.kind}`);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("composer.installFailed"));
    }
  };

  if (!manifest) {
    return (
      <div className="h-screen w-screen flex items-center justify-center bg-background">
        <Loader2 className="h-6 w-6 animate-spin text-primary" />
      </div>
    );
  }

  return (
    <ComposerCanvas
      initialManifest={manifest}
      onSaveDraft={handleSaveDraft}
      onInstall={handleInstall}
      onBack={() => router.push("/admin/apps")}
    />
  );
}
