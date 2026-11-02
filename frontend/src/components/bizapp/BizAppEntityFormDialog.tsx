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

import { useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import toast from "react-hot-toast";
import { Loader2 } from "lucide-react";

import type { BizAppEntity, BizAppField, BizAppInstalledAppView, BizAppRecordView } from "@/types";
import { useApiClient } from "@/hooks/useApiClient";
import { createBizAppRecordApi, updateBizAppRecordApi } from "@/lib/bizapp-api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

/**
 * Create/patch one record against the generic store, regenerated straight
 * from the manifest entity so unknown fields cannot even be typed (the API
 * rejects them with 400 regardless).
 */
export function BizAppEntityFormDialog({ app, entity, record, open, onClose, onSaved }: {
  app: BizAppInstalledAppView;
  entity: BizAppEntity;
  record: BizAppRecordView | null;
  open: boolean;
  onClose: () => void;
  onSaved: () => void | Promise<void>;
}) {
  const t = useTranslations("BizApps");
  const locale = useLocale();

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{record ? t("record.editTitle") : t("record.createTitle")}</DialogTitle>
          <DialogDescription>
            {(entity.label ? (locale === "vi" ? entity.label.vi : entity.label.en) : null) ?? entity.name}
          </DialogDescription>
        </DialogHeader>
        {open && (
          <BizAppRecordForm
            // Remounting per target resets the draft state cleanly instead of
            // resetting it from an effect.
            key={`${entity.name}:${record?.id ?? "create"}`}
            app={app}
            entity={entity}
            record={record}
            onClose={onClose}
            onSaved={onSaved}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

function BizAppRecordForm({ app, entity, record, onClose, onSaved }: {
  app: BizAppInstalledAppView;
  entity: BizAppEntity;
  record: BizAppRecordView | null;
  onClose: () => void;
  onSaved: () => void | Promise<void>;
}) {
  const t = useTranslations("BizApps");
  const locale = useLocale();
  const { request } = useApiClient();
  const [values, setValues] = useState<Record<string, string>>(() => initialDraft(entity, record));
  const [submitting, setSubmitting] = useState(false);

  const fieldLabel = useMemo(
    () => (field: BizAppField) =>
      (field.label ? (locale === "vi" ? field.label.vi : field.label.en) : null) ?? field.name,
    [locale],
  );

  const onField = (name: string, value: string) => {
    setValues((current) => ({ ...current, [name]: value }));
  };

  const onSubmit = async () => {
    const payload: Record<string, unknown> = {};
    for (const field of entity.fields) {
      const raw = (values[field.name] ?? "").trim();
      if (raw === "") continue;
      switch (field.type) {
        case "number": {
          const number = Number(raw);
          payload[field.name] = Number.isFinite(number) ? number : raw;
          break;
        }
        case "boolean":
          payload[field.name] = raw === "true";
          break;
        default:
          payload[field.name] = raw;
      }
    }
    setSubmitting(true);
    try {
      if (record) {
        await updateBizAppRecordApi(request, record.appKind, entity.name, record.id, payload);
      } else {
        await createBizAppRecordApi(request, app.kind, entity.name, payload);
      }
      setSubmitting(false);
      await onSaved();
    } catch (cause) {
      setSubmitting(false);
      toast.error(cause instanceof Error ? cause.message : t("fallback.save"));
    }
  };

  return (
    <form
      className="space-y-4"
      onSubmit={(event) => {
        event.preventDefault();
        void onSubmit();
      }}
    >
      {entity.fields.map((field) => (
        <div key={field.name} className="space-y-1.5">
          <Label htmlFor={`bizapp-${entity.name}-${field.name}`} className="text-xs">
            {fieldLabel(field)}
            {field.required ? <span className="text-destructive"> *</span> : null}
          </Label>
          {field.type === "enum" ? (
            <Select
              value={values[field.name] ?? ""}
              onValueChange={(value) => onField(field.name, value ?? "")}
            >
              <SelectTrigger id={`bizapp-${entity.name}-${field.name}`}>
                <SelectValue placeholder={t("record.pick", { field: fieldLabel(field) })} />
              </SelectTrigger>
              <SelectContent>
                {(field.options ?? []).map((option) => (
                  <SelectItem key={option} value={option}>{option}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          ) : field.type === "boolean" ? (
            <Select
              value={values[field.name] ?? "false"}
              onValueChange={(value) => onField(field.name, value ?? "false")}
            >
              <SelectTrigger id={`bizapp-${entity.name}-${field.name}`}>
                <SelectValue>{values[field.name] === "true" ? "✓" : "✗"}</SelectValue>
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="true">{t("boolean.true")}</SelectItem>
                <SelectItem value="false">{t("boolean.false")}</SelectItem>
              </SelectContent>
            </Select>
          ) : (
            <Input
              id={`bizapp-${entity.name}-${field.name}`}
              type={field.type === "date" ? "date" : field.type === "number" ? "number" : "text"}
              value={values[field.name] ?? ""}
              maxLength={field.maxLen ?? undefined}
              required={field.required}
              onChange={(event) => onField(field.name, event.target.value)}
            />
          )}
        </div>
      ))}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>{t("cancel")}</Button>
        <Button type="submit" disabled={submitting}>
          {submitting && <Loader2 className="mr-2 size-4 animate-spin" />}
          {t("record.save")}
        </Button>
      </DialogFooter>
    </form>
  );
}

/** Prefill the draft from the record being edited (create starts empty). */
function initialDraft(entity: BizAppEntity, record: BizAppRecordView | null): Record<string, string> {
  if (!record) return {};
  const next: Record<string, string> = {};
  for (const field of entity.fields) {
    const raw = record.payload[field.name];
    next[field.name] = raw === null || raw === undefined ? "" : String(raw);
  }
  return next;
}
