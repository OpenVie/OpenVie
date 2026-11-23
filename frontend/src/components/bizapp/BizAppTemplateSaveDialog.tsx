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
import { useTranslations } from "next-intl";
import toast from "react-hot-toast";
import { Loader2, Share2 } from "lucide-react";

import type { BizAppManifest } from "@/types";
import { useApiClient } from "@/hooks/useApiClient";
import { saveBizAppTemplateApi } from "@/lib/bizapp-api";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

/**
 * Publishes a manifest into the company catalog
 * (`POST /marketplace/bizapps`, ORG_OWNER). Used by the composer and by the
 * admin installed-app cards; the original draft/installation is untouched.
 */
interface BizAppTemplateSaveDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  manifest: BizAppManifest;
  defaultName?: string;
  onSaved?: () => void | Promise<void>;
}

/**
 * Mount the form only while open so its initial name/summary come from a fresh
 * state, without a setState-in-effect reset.
 */
export function BizAppTemplateSaveDialog(props: BizAppTemplateSaveDialogProps) {
  if (!props.open) return null;
  return <TemplateSaveDialogBody {...props} />;
}

function TemplateSaveDialogBody({
  open,
  onOpenChange,
  manifest,
  defaultName,
  onSaved,
}: BizAppTemplateSaveDialogProps) {
  const t = useTranslations("BizApps.templateDialog");
  const { request } = useApiClient();
  const fallbackName = defaultName
    || manifest.name?.en
    || manifest.name?.vi
    || manifest.kind;
  const [name, setName] = useState(fallbackName);
  const [summary, setSummary] = useState("");
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    const trimmed = name.trim();
    if (!trimmed || saving) return;
    setSaving(true);
    try {
      await saveBizAppTemplateApi(request, {
        name: trimmed,
        summary: summary.trim() || undefined,
        manifest: manifest as unknown as Record<string, unknown>,
      });
      toast.success(t("saved"));
      await onSaved?.();
      onOpenChange(false);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("failed"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!saving) onOpenChange(next); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Share2 className="size-4" />
            {t("title")}
          </DialogTitle>
          <DialogDescription>
            {t("description", { kind: manifest.kind })}
          </DialogDescription>
        </DialogHeader>
        <form
          className="space-y-4"
          onSubmit={(event) => { event.preventDefault(); void submit(); }}
        >
          <div className="space-y-1.5">
            <Label htmlFor="templateName">{t("name")}</Label>
            <Input
              id="templateName"
              autoFocus
              maxLength={120}
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder={t("namePlaceholder")}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="templateSummary">{t("summary")}</Label>
            <Textarea
              id="templateSummary"
              rows={3}
              maxLength={500}
              value={summary}
              onChange={(event) => setSummary(event.target.value)}
              placeholder={t("summaryPlaceholder")}
            />
          </div>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={saving}
              onClick={() => onOpenChange(false)}
            >
              {t("cancel")}
            </Button>
            <Button type="submit" disabled={saving || !name.trim()}>
              {saving ? <Loader2 className="mr-1.5 size-4 animate-spin" /> : <Share2 className="mr-1.5 size-4" />}
              {saving ? t("saving") : t("save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
