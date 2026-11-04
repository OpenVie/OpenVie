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
import { Link } from "@/i18n/navigation";
import {
  AlertCircle,
  AlertTriangle,
  AppWindow,
  CheckCircle2,
  ExternalLink,
  Layers,
  Loader2,
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
  kind: "bizapp.install" | "bizapp.uninstall" | "bizapp.catalog" | "bizapp.suggest" | "bizapp.draft" | "bizapp.open_composer";
  status: "PROPOSED" | "EXECUTING" | "EXECUTED" | "FAILED" | "DENIED" | "EXPIRED";
  appKind?: string;
  appName?: string;
  version?: number;
  description?: string;
  requiresRole?: string;
  executedBy?: string | null;
  executedAt?: string | null;
  result?: Record<string, unknown> | null;
  payload?: Record<string, unknown> | null;
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

  const raw = initialAction as unknown as BizAppActionData;
  const [status, setStatus] = useState<BizAppActionData["status"]>(raw.status || "PROPOSED");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(
    raw.result && typeof raw.result.message === "string" ? raw.result.message : null
  );

  const actionId = raw.actionId;
  const kind = raw.kind || "bizapp.catalog";
  const appKind = raw.appKind || "";
  const appName = raw.appName || appKind;
  const description = raw.description;
  const version = raw.version ?? 1;

  async function handleExecute() {
    if (!messageId || !actionId) return;
    setStatus("EXECUTING");
    setErrorMessage(null);
    try {
      const resp = await executeChatActionApi(request, messageId, actionId);
      setStatus("EXECUTED");
      const resultMsg = resp.action && typeof resp.action.result === "object" && resp.action.result !== null && "message" in resp.action.result
        ? String((resp.action.result as Record<string, unknown>).message)
        : t("executed");
      setSuccessMessage(resultMsg);
      if (onActionExecuted) {
        onActionExecuted(resp);
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

  return (
    <Card
      role="region"
      aria-label={t("installTitle")}
      className="my-3 border-border/80 bg-card/90 shadow-sm transition-all hover:border-border"
    >
      <CardHeader className="p-4 pb-2">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            {kind === "bizapp.install" && <AppWindow className="size-4 text-primary" />}
            {kind === "bizapp.uninstall" && <AlertTriangle className="size-4 text-destructive" />}
            {kind === "bizapp.catalog" && <Layers className="size-4 text-primary" />}
            {kind === "bizapp.suggest" && <Sparkles className="size-4 text-primary" />}
            {kind === "bizapp.draft" && <Sparkles className="size-4 text-primary" />}
            <CardTitle className="text-sm font-semibold text-foreground">
              {appName ? `${appName} (v${version})` : t("catalogTitle")}
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
            {!isExecuted && !isFailed && !isDenied && (
              <Badge variant="secondary" className="text-xs font-normal">
                {kind === "bizapp.install" ? t("installTitle") : kind === "bizapp.uninstall" ? t("uninstallTitle") : t("catalogTitle")}
              </Badge>
            )}
          </div>
        </div>
      </CardHeader>

      <CardContent className="p-4 pt-1 text-xs text-muted-foreground">
        {description && <p className="mb-2 leading-relaxed">{description}</p>}
        {kind === "bizapp.uninstall" && status === "PROPOSED" && (
          <p className="text-xs text-destructive/90">{t("uninstallWarning")}</p>
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

      <CardFooter className="flex flex-wrap items-center justify-end gap-2 p-4 pt-0">
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
          <div className="flex gap-2">
            {Boolean(raw.payload?.draftId) && (
              <Link href={`/admin/apps/compose/${String(raw.payload?.draftId)}`} className={cn(buttonVariants({ size: "sm", variant: "outline" }), "text-xs")}>
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
                className="text-xs"
              >
                {isExecuting && <Loader2 className="mr-1.5 size-3.5 animate-spin" />}
                {isExecuting ? t("executing") : t("installToWorkspace")}
              </Button>
            )}
          </div>
        )}
      </CardFooter>
    </Card>
  );
}
