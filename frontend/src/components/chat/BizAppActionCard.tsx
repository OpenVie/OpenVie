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
import { Link, useRouter } from "@/i18n/navigation";
import {
  AlertCircle,
  AlertTriangle,
  AppWindow,
  CheckCircle2,
  ExternalLink,
  FileEdit,
  FilePlus,
  Layers,
  Loader2,
  Send,
  Sparkles,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { useApiClient } from "@/hooks/useApiClient";
import { useAuthStore } from "@/components/providers/StoreProvider";
import { executeChatActionApi } from "@/lib/bizapp-api";
import type { AssistantMessageResponse } from "@/types";

export interface BizAppActionData {
  type: "bizapp_action";
  actionId: string;
  kind: "bizapp.install" | "bizapp.uninstall" | "bizapp.catalog" | "bizapp.suggest" | "bizapp.draft" | "bizapp.open_composer"
    | "bizapp.record.create" | "bizapp.record.update" | "bizapp.record.archive" | "bizapp.record.submit";
  status: "PROPOSED" | "EXECUTING" | "EXECUTED" | "FAILED" | "DENIED" | "EXPIRED" | "INVALID";
  appKind?: string;
  appName?: string;
  version?: number;
  description?: string;
  requiresRole?: string;
  executedBy?: string | null;
  executedAt?: string | null;
  result?: Record<string, unknown> | null;
  payload?: Record<string, unknown> | null;
  entity?: string;
  entityLabel?: string;
  recordId?: string;
  fields?: Record<string, unknown>;
  fieldLabels?: Record<string, string>;
  diff?: Record<string, { before: unknown; after: unknown }>;
  invalid?: Array<{ field: string; code: string; message: string }>;
  payloadHash?: string;
}
export interface BizAppActionCardProps {
  action: BizAppActionData | Record<string, unknown>;
  messageId?: string;
  onActionExecuted?: (updated: AssistantMessageResponse) => void;
}

export function BizAppActionCard({ action: initialAction, messageId, onActionExecuted }: BizAppActionCardProps) {
  const t = useTranslations("Chat.bizappAction");
  const { request } = useApiClient();
  const user = useAuthStore((s) => s.user);
  const isOwner = user?.orgRole === "ORG_OWNER";
  const router = useRouter();
  const raw = initialAction as unknown as BizAppActionData;
  const [status, setStatus] = useState<BizAppActionData["status"]>(raw.status || "PROPOSED");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(
    raw.result && typeof raw.result.message === "string" ? raw.result.message : null
  );
  const [actionResult, setActionResult] = useState<Record<string, unknown> | null>(
    (raw.result as Record<string, unknown>) || null
  );

  const actionId = raw.actionId;
  const kind = raw.kind || "bizapp.catalog";
  const appKind = raw.appKind || "";
  const appName = raw.appName || appKind;
  const description = raw.description;
  const version = raw.version ?? 1;

  async function handleExecute() {
    if (!actionId) return;
    setErrorMessage(null);
    setStatus("EXECUTING");
    try {
      const resp = await executeChatActionApi(request, messageId, actionId);
      setStatus("EXECUTED");
      const resObj = resp.action && typeof resp.action.result === "object" && resp.action.result !== null
        ? (resp.action.result as Record<string, unknown>)
        : null;
      setActionResult(resObj);
      const resultMsg = resObj && "message" in resObj
        ? String(resObj.message)
        : t("executed");
      setSuccessMessage(resultMsg);
      if (onActionExecuted) {
        onActionExecuted(resp);
      }

      const draftId = resObj?.draftId || (resp.action?.payload as Record<string, unknown> | undefined)?.draftId;
      if (draftId) {
        router.push(`/admin/apps/compose/${String(draftId)}`);
      }
    } catch (err: unknown) {
      setStatus("FAILED");
      const message = err instanceof Error ? err.message : t("failed");
      setErrorMessage(message);
    }
  }

  const isExecuting = status === "EXECUTING";
  const isExecuted = status === "EXECUTED";
  const isFailed = status === "FAILED";
  const isDenied = status === "DENIED";

  const isInvalid = status === "INVALID";
  const entityTitle = raw.entityLabel || raw.entity;
  const cardTitle = entityTitle && appName
    ? `${appName} — ${entityTitle}`
    : appName
    ? `${appName} (v${version})`
    : kind === "bizapp.draft"
    ? t("draftTitle")
    : t("catalogTitle");
  return (
    <Card
      role="region"
      aria-label={t("installTitle")}
      className="my-3 border-border/80 bg-card/90 shadow-sm transition-all hover:border-border gap-0"
    >
      <CardHeader className="p-4 pb-3">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            {kind === "bizapp.install" && <AppWindow className="size-4 text-primary" />}
            {kind === "bizapp.uninstall" && <AlertTriangle className="size-4 text-destructive" />}
            {kind === "bizapp.catalog" && <Layers className="size-4 text-primary" />}
            {kind === "bizapp.suggest" && <Sparkles className="size-4 text-primary" />}
            {kind === "bizapp.draft" && <Sparkles className="size-4 text-primary" />}
            {kind === "bizapp.record.create" && <FilePlus className="size-4 text-emerald-600 dark:text-emerald-400" />}
            {kind === "bizapp.record.update" && <FileEdit className="size-4 text-blue-600 dark:text-blue-400" />}
            {kind === "bizapp.record.archive" && <AlertTriangle className="size-4 text-destructive" />}
            {kind === "bizapp.record.submit" && <Send className="size-4 text-purple-600 dark:text-purple-400" />}
            <CardTitle className="text-sm font-semibold text-foreground">
              {cardTitle}
            </CardTitle>
          </div>
          <div>
            {isExecuted && (
              <Badge variant="outline" className="border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400">
                <CheckCircle2 className="mr-1 size-3" />
                {t("executed")}
              </Badge>
            )}
            {isFailed && (
              <Badge variant="destructive">
                <AlertCircle className="mr-1 size-3" />
                {t("failed")}
              </Badge>
            )}
            {isDenied && (
              <Badge variant="secondary" className="text-amber-600 dark:text-amber-400">
                <AlertCircle className="mr-1 size-3" />
                {t("denied")}
              </Badge>
            )}
            {isInvalid && (
              <Badge variant="destructive" className="bg-amber-500/15 text-amber-700 dark:text-amber-300">
                <AlertCircle className="mr-1 size-3" />
                {t("invalid")}
              </Badge>
            )}
            {!isExecuted && !isFailed && !isDenied && !isInvalid && (
              <Badge variant="secondary" className="text-xs font-normal">
                {kind === "bizapp.install"
                  ? t("installTitle")
                  : kind === "bizapp.uninstall"
                  ? t("uninstallTitle")
                  : kind === "bizapp.record.create"
                  ? t("recordCreateTitle")
                  : kind === "bizapp.record.update"
                  ? t("recordUpdateTitle")
                  : kind === "bizapp.record.archive"
                  ? t("recordArchiveTitle")
                  : kind === "bizapp.record.submit"
                  ? t("recordSubmitTitle")
                  : t("catalogTitle")}
              </Badge>
            )}
          </div>
        </div>
      </CardHeader>
      {Boolean(
        description ||
        (kind === "bizapp.uninstall" && status === "PROPOSED") ||
        (kind === "bizapp.record.archive" && status === "PROPOSED") ||
        (raw.fields && Object.keys(raw.fields).length > 0) ||
        (raw.invalid && raw.invalid.length > 0) ||
        (successMessage && isExecuted) ||
        (errorMessage && isFailed)
      ) && (
        <CardContent className="px-4 pb-3 pt-0 text-xs text-muted-foreground">
          {description && <p className="mb-2 leading-relaxed">{description}</p>}
        {kind === "bizapp.uninstall" && status === "PROPOSED" && (
          <p className="text-xs text-destructive/90">{t("uninstallWarning")}</p>
        )}
        {kind === "bizapp.record.archive" && status === "PROPOSED" && (
          <p className="text-xs text-destructive/90">{t("recordArchiveWarning")}</p>
        )}

        {raw.fields && Object.keys(raw.fields).length > 0 && (
          <div className="my-2 overflow-x-auto rounded border border-border/60 bg-muted/20">
            <table className="w-full text-left text-xs">
              <tbody>
                {Object.entries(raw.fields).map(([k, v]) => (
                  <tr key={k} className="border-b border-border/30 last:border-b-0">
                    <td className="w-1/3 px-2 py-1 font-medium text-foreground">{raw.fieldLabels?.[k] || k}</td>
                    <td className="px-2 py-1 text-muted-foreground">{String(v)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {raw.invalid && raw.invalid.length > 0 && (
          <div className="my-2 rounded border border-amber-500/30 bg-amber-500/10 p-2 text-amber-700 dark:text-amber-300">
            <p className="font-medium">{t("invalidTitle")}:</p>
            <ul className="mt-1 list-inside list-disc">
              {raw.invalid.map((err, idx) => (
                <li key={idx}>{err.message || err.field}</li>
              ))}
            </ul>
          </div>
        )}

        <div aria-live="polite" className="mt-2 text-xs">
          {successMessage && isExecuted && (
            <p className="text-emerald-600 dark:text-emerald-400">{successMessage}</p>
          )}
          {errorMessage && isFailed && (
            <p className="text-destructive">{errorMessage}</p>
          )}
        </div>
        </CardContent>
      )}

      <CardFooter className="flex flex-wrap items-center justify-end gap-2 px-4 py-3 bg-muted/30 border-t border-border/40 rounded-b-xl">
        {kind === "bizapp.install" && !isExecuted && (
          <Button
            size="sm"
            disabled={!isOwner || isExecuting}
            onClick={() => void handleExecute()}
            title={!isOwner ? t("onlyOwnerCanExecute") : undefined}
            className="text-xs"
          >
            {isExecuting && <Loader2 className="mr-1.5 size-3.5 animate-spin" />}
            {isExecuting ? t("executing") : t("installConfirm")}
          </Button>
        )}

        {kind === "bizapp.uninstall" && !isExecuted && (
          <Button
            size="sm"
            variant="destructive"
            disabled={!isOwner || isExecuting}
            onClick={() => void handleExecute()}
            title={!isOwner ? t("onlyOwnerCanExecute") : undefined}
            className="text-xs"
          >
            {isExecuting && <Loader2 className="mr-1.5 size-3.5 animate-spin" />}
            {isExecuting ? t("executing") : t("uninstallConfirm")}
          </Button>
        )}

        {kind === "bizapp.catalog" && (
          <Link href="/apps" className={cn(buttonVariants({ size: "sm", variant: "outline" }), "text-xs")}>
            {t("openCatalog")}
            <ExternalLink className="ml-1.5 size-3.5" />
          </Link>
        )}
        {kind === "bizapp.draft" && (
          <div className="flex items-center gap-2">
            {Boolean(actionResult?.draftId || raw.payload?.draftId || (raw.result as Record<string, unknown> | undefined)?.draftId) && (
              <Link
                href={`/admin/apps/compose/${String(actionResult?.draftId || raw.payload?.draftId || (raw.result as Record<string, unknown> | undefined)?.draftId)}`}
                className={cn(buttonVariants({ size: "sm" }), "text-xs font-semibold")}
              >
                {t("openComposer")}
                <ExternalLink className="ml-1.5 size-3.5" />
              </Link>
            )}
            {!isExecuted && (
              <Button
                size="sm"
                disabled={!isOwner || isExecuting}
                onClick={() => void handleExecute()}
                title={!isOwner ? t("onlyOwnerCanExecute") : undefined}
                className="text-xs font-semibold"
              >
                {isExecuting && <Loader2 className="mr-1.5 size-3.5 animate-spin" />}
                {isExecuting ? t("executing") : t("createAppConfirm")}
              </Button>
            )}
          </div>
        )}

        {kind === "bizapp.record.create" && !isExecuted && (
          <Button
            size="sm"
            disabled={isInvalid || isExecuting}
            onClick={() => void handleExecute()}
            className="text-xs"
          >
            {isExecuting && <Loader2 className="mr-1.5 size-3.5 animate-spin" />}
            {isExecuting ? t("executing") : t("recordCreateConfirm")}
          </Button>
        )}

        {kind === "bizapp.record.update" && !isExecuted && (
          <Button
            size="sm"
            disabled={isInvalid || isExecuting}
            onClick={() => void handleExecute()}
            className="text-xs"
          >
            {isExecuting && <Loader2 className="mr-1.5 size-3.5 animate-spin" />}
            {isExecuting ? t("executing") : t("recordUpdateConfirm")}
          </Button>
        )}

        {kind === "bizapp.record.archive" && !isExecuted && (
          <Button
            size="sm"
            variant="destructive"
            disabled={isInvalid || isExecuting}
            onClick={() => void handleExecute()}
            className="text-xs"
          >
            {isExecuting && <Loader2 className="mr-1.5 size-3.5 animate-spin" />}
            {isExecuting ? t("executing") : t("recordArchiveConfirm")}
          </Button>
        )}

        {kind === "bizapp.record.submit" && !isExecuted && (
          <Button
            size="sm"
            disabled={isInvalid || isExecuting}
            onClick={() => void handleExecute()}
            className="text-xs"
          >
            {isExecuting && <Loader2 className="mr-1.5 size-3.5 animate-spin" />}
            {isExecuting ? t("executing") : t("recordSubmitConfirm")}
          </Button>
        )}
      </CardFooter>
    </Card>
  );
}
