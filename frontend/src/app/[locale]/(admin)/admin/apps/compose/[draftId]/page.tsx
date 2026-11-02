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
import { useRouter } from "@/i18n/navigation";
import toast from "react-hot-toast";
import { Loader2 } from "lucide-react";
import type { BizAppManifest } from "@/types";
import { useApiClient } from "@/hooks/useApiClient";
import { ComposerCanvas } from "@/components/bizapp/composer/ComposerCanvas";
import { installBizAppApi } from "@/lib/bizapp-api";

export default function ComposeDraftPage({
  params,
}: {
  params: Promise<{ draftId: string }>;
}) {
  const { draftId } = use(params);
  const router = useRouter();
  const { request } = useApiClient();

  const [manifest, setManifest] = useState<BizAppManifest | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    const fetchDraft = async () => {
      try {
        const res = await request(`/bizapps/drafts/${draftId}`);
        if (!active) return;
        if (res.ok) {
          const data = await res.json();
          setManifest(data.manifest);
        } else {
          setError("Failed to load draft");
        }
      } catch (err) {
        if (!active) return;
        setError(err instanceof Error ? err.message : "Error loading draft");
      } finally {
        if (active) setLoading(false);
      }
    };
    void fetchDraft();
    return () => {
      active = false;
    };
  }, [draftId, request]);

  const handleSaveDraft = async (updated: BizAppManifest) => {
    try {
      const res = await request(`/bizapps/drafts/${draftId}`, {
        method: "PUT",
        body: JSON.stringify({
          id: draftId,
          title: updated.name?.en || updated.kind,
          source: "canvas",
          manifest: updated,
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

  const handleInstall = async (updated: BizAppManifest) => {
    try {
      await installBizAppApi(request, { draftId });
      toast.success("App installed successfully from draft.");
      router.push(`/apps/${updated.kind}`);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Error installing app");
    }
  };

  if (loading) {
    return (
      <div className="h-[60vh] flex items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-primary" />
      </div>
    );
  }

  if (error || !manifest) {
    return (
      <div className="p-8 text-center text-destructive">
        <p>{error || "Draft not found"}</p>
      </div>
    );
  }

  return (
    <div className="p-4 space-y-4">
      <ComposerCanvas
        initialManifest={manifest}
        draftId={draftId}
        onSaveDraft={handleSaveDraft}
        onInstall={handleInstall}
      />
    </div>
  );
}
