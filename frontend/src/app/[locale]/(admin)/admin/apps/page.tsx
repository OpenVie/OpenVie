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
import { useFormatter, useTranslations } from "next-intl";
import toast from "react-hot-toast";
import { AlertCircle, EyeOff, Loader2, Plus, RefreshCw, Trash2 } from "lucide-react";

import type {
  BizAppCredentialView,
  BizAppInstalledAppView,
  BizAppTemplateView,
} from "@/types";
import { useApiClient } from "@/hooks/useApiClient";
import {
  createBizAppCredentialApi,
  deleteBizAppCredentialApi,
  deleteBizAppTemplateApi,
  installBizAppApi,
  listBizAppCredentialsApi,
  listBizAppTemplatesApi,
  listInstalledBizAppsApi,
  uninstallBizAppApi,
} from "@/lib/bizapp-api";
import { useRouter } from "@/i18n/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

type CredentialFormat = "api_key" | "bearer" | "basic";

type ConfirmTarget =
  | { action: "uninstall"; kind: string }
  | { action: "template"; templateId: string; name: string }
  | { action: "credential"; credentialId: string; name: string };

/**
 * Owner marketplace console: browse the two-layer catalog (platform-bundled
 * plus org-crafted templates), install/uninstall apps, delete org templates,
 * and manage the credential vault backing `external_call` blocks. The API
 * re-enforces `ORG_OWNER` on every route.
 */
export default function AdminAppsPage() {
  const t = useTranslations("BizApps");
  const format = useFormatter();
  const { request } = useApiClient();
  const router = useRouter();

  const [templates, setTemplates] = useState<BizAppTemplateView[]>([]);
  const [installed, setInstalled] = useState<BizAppInstalledAppView[]>([]);
  const [credentials, setCredentials] = useState<BizAppCredentialView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [showCredentialForm, setShowCredentialForm] = useState(false);
  const [confirmTarget, setConfirmTarget] = useState<ConfirmTarget | null>(null);

  // Credential dialog: plain controlled inputs keep the "user" field optional
  // (the API uses it only for the `basic` format).
  const [credentialName, setCredentialName] = useState("");
  const [credentialFormat, setCredentialFormat] = useState<CredentialFormat>("api_key");
  const [credentialSecret, setCredentialSecret] = useState("");
  const [credentialUser, setCredentialUser] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const resetCredentialForm = () => {
    setCredentialName("");
    setCredentialFormat("api_key");
    setCredentialSecret("");
    setCredentialUser("");
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setTemplates(await listBizAppTemplatesApi(request));
      setInstalled(await listInstalledBizAppsApi(request));
      try {
        setCredentials(await listBizAppCredentialsApi(request));
      } catch {
        // Vault access can fail per-environment (no BIZAPP_ENC_KEY); the
        // install surface above must still render.
        setCredentials([]);
      }
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

  const installedKinds = new Set(installed.map((app) => app.kind));

  const templateRef = (template: BizAppTemplateView): string =>
    template.source === "platform" ? `platform:${template.kind}` : String(template.id ?? "");

  const templateName = (template: BizAppTemplateView): string =>
    template.nameVi || template.name;

  const onInstall = async (template: BizAppTemplateView) => {
    setBusy(`install:${templateRef(template)}`);
    try {
      const result = await installBizAppApi(request, templateRef(template));
      toast.success(t("admin.installSuccess", { kind: result.kind }));
      await load();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("admin.fallbackInstall"));
    } finally {
      setBusy(null);
    }
  };

  const confirmName = (target: ConfirmTarget): string => {
    switch (target.action) {
      case "uninstall": return target.kind;
      case "template": return target.name;
      case "credential": return target.name;
    }
  };

  const onConfirm = async () => {
    if (!confirmTarget) return;
    const target = confirmTarget;
    setConfirmTarget(null);
    setBusy(target.action);
    try {
      if (target.action === "uninstall") {
        await uninstallBizAppApi(request, target.kind);
        toast.success(t("admin.uninstallSuccess", { kind: target.kind }));
      } else if (target.action === "template") {
        await deleteBizAppTemplateApi(request, target.templateId);
        toast.success(t("admin.templateDeleted"));
      } else {
        await deleteBizAppCredentialApi(request, target.credentialId);
        toast.success(t("admin.credentialDeleted"));
      }
      await load();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("admin.fallbackDelete"));
    } finally {
      setBusy(null);
    }
  };

  const onCreateCredential = async () => {
    if (!credentialName.trim() || !credentialSecret.trim()) return;
    setSubmitting(true);
    try {
      await createBizAppCredentialApi(request, {
        name: credentialName.trim(),
        format: credentialFormat,
        secret: credentialSecret.trim(),
        user: credentialUser.trim() || undefined,
      });
      toast.success(t("admin.credentialCreated"));
      resetCredentialForm();
      setShowCredentialForm(false);
      await load();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("admin.fallbackCredential"));
    } finally {
      setSubmitting(false);
    }
  };

  const formatLabel = (value: string): string => {
    switch (value) {
      case "api_key": return t("admin.formatApiKey");
      case "bearer": return t("admin.formatBearer");
      case "basic": return t("admin.formatBasic");
      default: return value;
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-4">
        <div>
          <h2 className="text-xl font-semibold text-foreground">{t("admin.title")}</h2>
          <p className="mt-1 text-sm text-muted-foreground">{t("admin.subtitle")}</p>
        </div>
        <Button size="sm" variant="outline" onClick={() => void load()}>
          <RefreshCw className="mr-1 size-4" />{t("refresh")}
        </Button>
      </div>

      {error && (
        <div role="alert" className="flex items-center justify-between rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
          <span className="flex items-center gap-2"><AlertCircle className="size-4" />{error}</span>
          <Button size="sm" variant="outline" onClick={() => void load()}>{t("retry")}</Button>
        </div>
      )}

      {loading ? (
        <div className="space-y-4">
          <Skeleton className="h-48 w-full" />
          <Skeleton className="h-32 w-full" />
        </div>
      ) : (
        <>
          {/* Marketplace catalog */}
          <Card>
            <CardContent className="p-5">
              <div className="flex items-center justify-between">
                <h3 className="font-medium">{t("admin.templates")}</h3>
              </div>
              {templates.length === 0 ? (
                <p className="py-8 text-center text-sm text-muted-foreground">{t("admin.emptyTemplates")}</p>
              ) : (
                <div className="mt-3 space-y-2">
                  {templates.map((template) => (
                    <div key={`${template.source}:${template.id ?? template.kind}`}
                      className="flex flex-wrap items-center justify-between gap-3 rounded-xl border p-4">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <p className="truncate font-medium">{templateName(template)}</p>
                          <Badge variant={template.source === "platform" ? "default" : "outline"}>
                            {template.source === "platform" ? t("admin.sourcePlatform") : t("admin.sourceOrg")}
                          </Badge>
                        </div>
                        {template.summary && (
                          <p className="mt-1 max-w-xl truncate text-xs text-muted-foreground">{template.summary}</p>
                        )}
                      </div>
                      <div className="flex shrink-0 items-center gap-2">
                        {installedKinds.has(template.kind) ? (
                          <Badge variant="secondary">{t("admin.installedBadge")}</Badge>
                        ) : (
                          <Button
                            size="sm"
                            disabled={busy === `install:${templateRef(template)}` || Boolean(busy)}
                            onClick={() => void onInstall(template)}
                          >
                            {busy === `install:${templateRef(template)}` && <Loader2 className="mr-1 size-3.5 animate-spin" />}
                            {t("admin.install")}
                          </Button>
                        )}
                        {template.source === "org" && template.id && (
                          <Button
                            size="icon" variant="ghost" className="size-8 text-destructive"
                            aria-label={t("admin.deleteTemplate")}
                            disabled={Boolean(busy)}
                            onClick={() => setConfirmTarget({ action: "template", templateId: templateRef(template), name: templateName(template) })}
                          >
                            <Trash2 className="size-4" />
                          </Button>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Installed apps */}
          <Card>
            <CardContent className="p-5">
              <h3 className="font-medium">{t("admin.installed")}</h3>
              {installed.length === 0 ? (
                <p className="py-8 text-center text-sm text-muted-foreground">{t("admin.emptyInstalled")}</p>
              ) : (
                <div className="mt-3 space-y-2">
                  {installed.map((app) => (
                    <div key={app.kind} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border p-4">
                      <div className="min-w-0">
                        <p className="truncate font-medium">{app.nameVi || app.nameEn}</p>
                        <p className="mt-1 text-xs text-muted-foreground">
                          {t("appCard", { kind: app.kind, version: app.version })}
                        </p>
                      </div>
                      <div className="flex shrink-0 items-center gap-2">
                        <Button size="sm" variant="outline" onClick={() => router.push(`/apps/${app.kind}`)}>
                          {t("admin.open")}
                        </Button>
                        <Button
                          size="sm" variant="outline"
                          disabled={Boolean(busy)}
                          onClick={() => setConfirmTarget({ action: "uninstall", kind: app.kind })}
                        >
                          <EyeOff className="mr-1 size-3.5" />
                          {t("admin.uninstall")}
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Credential vault */}
          <Card>
            <CardContent className="p-5">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <h3 className="font-medium">{t("admin.credentials")}</h3>
                  <p className="mt-0.5 text-xs text-muted-foreground">{t("admin.credentialHint")}</p>
                </div>
                <Button size="sm" onClick={() => { resetCredentialForm(); setShowCredentialForm(true); }}>
                  <Plus className="mr-1 size-4" />{t("admin.addCredential")}
                </Button>
              </div>
              {credentials.length === 0 ? (
                <p className="py-8 text-center text-sm text-muted-foreground">{t("admin.emptyCredentials")}</p>
              ) : (
                <div className="mt-3 space-y-2">
                  {credentials.map((credential) => (
                    <div key={credential.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border p-4">
                      <div className="min-w-0">
                        <p className="truncate font-medium">{credential.name}</p>
                        <p className="mt-1 text-xs text-muted-foreground">
                          {formatLabel(credential.format)} · {format.dateTime(new Date(credential.createdAt), { dateStyle: "medium" })}
                        </p>
                      </div>
                      <Button
                        size="icon" variant="ghost" className="size-8 text-destructive"
                        aria-label={t("admin.credentialDeleted")}
                        disabled={Boolean(busy)}
                        onClick={() => setConfirmTarget({ action: "credential", credentialId: credential.id, name: credential.name })}
                      >
                        <Trash2 className="size-4" />
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </>
      )}

      {/* Credential create dialog */}
      <Dialog open={showCredentialForm} onOpenChange={setShowCredentialForm}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("admin.addCredential")}</DialogTitle>
            <DialogDescription>{t("admin.credentialHint")}</DialogDescription>
          </DialogHeader>
          <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void onCreateCredential(); }}>
            <div className="space-y-1.5">
              <Label htmlFor="bizappCredentialName">{t("admin.credentialName")}</Label>
              <Input
                id="bizappCredentialName" value={credentialName} maxLength={120}
                onChange={(event) => setCredentialName(event.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="bizappCredentialFormat">{t("admin.credentialFormat")}</Label>
              <Select value={credentialFormat} onValueChange={(value) => setCredentialFormat(value as CredentialFormat)}>
                <SelectTrigger id="bizappCredentialFormat">
                  <SelectValue>{formatLabel(credentialFormat)}</SelectValue>
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="api_key">{t("admin.formatApiKey")}</SelectItem>
                  <SelectItem value="bearer">{t("admin.formatBearer")}</SelectItem>
                  <SelectItem value="basic">{t("admin.formatBasic")}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {credentialFormat === "basic" && (
              <div className="space-y-1.5">
                <Label htmlFor="bizappCredentialUser">{t("admin.credentialUser")}</Label>
                <Input
                  id="bizappCredentialUser" value={credentialUser} maxLength={120}
                  onChange={(event) => setCredentialUser(event.target.value)}
                />
              </div>
            )}
            <div className="space-y-1.5">
              <Label htmlFor="bizappCredentialSecret">{t("admin.credentialSecret")}</Label>
              <Input
                id="bizappCredentialSecret" type="password" value={credentialSecret}
                autoComplete="new-password"
                onChange={(event) => setCredentialSecret(event.target.value)}
              />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setShowCredentialForm(false)}>
                {t("cancel")}
              </Button>
              <Button type="submit" disabled={submitting || !credentialName.trim() || !credentialSecret.trim()}>
                {submitting && <Loader2 className="mr-1 size-4 animate-spin" />}
                {t("admin.saveCredential")}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Confirm destructive action (uninstall / delete template / delete credential) */}
      <Dialog open={confirmTarget !== null} onOpenChange={(open) => { if (!open && !busy) setConfirmTarget(null); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("admin.confirmTitle")}</DialogTitle>
            <DialogDescription>
              {confirmTarget && t("admin.confirmDescription", { name: confirmName(confirmTarget) })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button type="button" variant="outline" disabled={Boolean(busy)} onClick={() => setConfirmTarget(null)}>
              {t("cancel")}
            </Button>
            <Button type="button" variant="destructive" disabled={Boolean(busy)} onClick={() => void onConfirm()}>
              {busy && <Loader2 className="mr-1 size-4 animate-spin" />}
              {t("admin.confirmAction")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
