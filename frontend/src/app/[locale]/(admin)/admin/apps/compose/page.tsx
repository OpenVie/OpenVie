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

import { useRouter } from "@/i18n/navigation";
import toast from "react-hot-toast";
import type { BizAppManifest } from "@/types";
import { useApiClient } from "@/hooks/useApiClient";
import { ComposerCanvas } from "@/components/bizapp/composer/ComposerCanvas";
import { installBizAppApi } from "@/lib/bizapp-api";

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
  const router = useRouter();
  const { request } = useApiClient();

  const handleSaveDraft = async (manifest: BizAppManifest) => {
    try {
      const res = await request("/bizapps/drafts/new", {
        method: "PUT",
        body: JSON.stringify({
          title: manifest.name?.en || manifest.kind,
          source: "canvas",
          manifest,
        }),
      });
      if (res.ok) {
        toast.success("Draft saved successfully.");
      } else {
        toast.error("Failed to save draft.");
      }
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Error saving draft");
    }
  };

  const handleInstall = async (manifest: BizAppManifest) => {
    try {
      await installBizAppApi(request, { manifest });
      toast.success("App installed successfully.");
      router.push(`/apps/${manifest.kind}`);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Error installing app");
    }
  };

  return (
    <div className="p-4 space-y-4">
      <ComposerCanvas
        initialManifest={DEFAULT_MANIFEST}
        onSaveDraft={handleSaveDraft}
        onInstall={handleInstall}
      />
    </div>
  );
}
