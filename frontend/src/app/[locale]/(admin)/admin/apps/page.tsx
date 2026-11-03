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
import {
  AlertCircle,
  Download,
  EyeOff,
  Loader2,
  Plus,
  RefreshCw,
  Sparkles,
  Trash2,
  Upload,
} from "lucide-react";

import type {
  BizAppCredentialView,
  BizAppDraftView,
  BizAppInstalledAppView,
  BizAppTemplateView,
} from "@/types";
import { useApiClient } from "@/hooks/useApiClient";
import {
  createBizAppCredentialApi,
  deleteBizAppCredentialApi,
  deleteBizAppDraftApi,
  deleteBizAppTemplateApi,
  exportBizAppTemplateApi,
  generateBizAppDraftApi,
  importBizAppTemplateApi,
  installBizAppApi,
  listBizAppCredentialsApi,
  listBizAppDraftsApi,
  listInstalledBizAppsApi,
  listBizAppTemplatesApi,
  remixBizAppTemplateApi,
  uninstallBizAppApi,
} from "@/lib/bizapp-api";
import { useRouter } from "@/i18n/navigation";
import { cn } from "@/lib/utils";
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
import { Textarea } from "@/components/ui/textarea";

type CredentialFormat = "api_key" | "bearer" | "basic";

type ConfirmTarget =
  | { action: "uninstall"; kind: string }
  | { action: "template"; templateId: string; name: string }
  | { action: "draft"; draftId: string; name: string }
  | { action: "credential"; credentialId: string; name: string };

/** Remix dialog target: the template being copied into a new draft. */
type RemixTarget = {
  templateRef: string;
  name: string;
};

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
  const [drafts, setDrafts] = useState<BizAppDraftView[]>([]);
  const [credentials, setCredentials] = useState<BizAppCredentialView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [showCredentialForm, setShowCredentialForm] = useState(false);
  const [confirmTarget, setConfirmTarget] = useState<ConfirmTarget | null>(null);

  // Composer entry points: AI drafting + template import land as drafts only
  // (install stays an explicit owner action), mirroring the backend contract.
  const [showAiDialog, setShowAiDialog] = useState(false);
  const [aiPrompt, setAiPrompt] = useState("");
  const [aiSubmitting, setAiSubmitting] = useState(false);

  const [showImportDialog, setShowImportDialog] = useState(false);
  const [importMode, setImportMode] = useState<"json" | "url">("json");
  const [importContent, setImportContent] = useState("");
  const [importUrl, setImportUrl] = useState("");
  const [importSha256, setImportSha256] = useState("");
  const [importSubmitting, setImportSubmitting] = useState(false);

  const [remixTarget, setRemixTarget] = useState<RemixTarget | null>(null);
  const [remixSummary, setRemixSummary] = useState("");
  const [remixSubmitting, setRemixSubmitting] = useState(false);

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

  const resetImportForm = () => {
    setImportMode("json");
    setImportContent("");
    setImportUrl("");
    setImportSha256("");
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setTemplates(await listBizAppTemplatesApi(request));
      setInstalled(await listInstalledBizAppsApi(request));
      try {
        setDrafts(await listBizAppDraftsApi(request));
      } catch {
        // Drafts are `ORG_OWNER`-scoped like the vault; the marketplace above
        // must still render if the surface is unavailable.
        setDrafts([]);
      }
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

  /** Display title for a draft row: explicit title, else the manifest name. */
  const draftTitle = (draft: BizAppDraftView): string => {
    if (draft.title?.trim()) return draft.title.trim();
    const name = draft.manifest?.name;
    if (name && typeof name === "object") {
      const localized = name as Record<string, string | undefined>;
      return localized.en || localized.vi || draft.id;
    }
    return draft.id;
  };

  /** A draft installs only when its stored validation passed. */
  const draftValid = (draft: BizAppDraftView): boolean =>
    (draft.validation as { ok?: boolean } | null)?.ok === true;

  const draftSourceLabel = (source: string): string => {
    switch (source) {
      case "ai": return t("admin.draftSourceAi");
      case "canvas": return t("admin.draftSourceCanvas");
      case "import": return t("admin.draftSourceImport");
      case "remix": return t("admin.draftSourceRemix");
      default: return source;
    }
  };

  const onInstallDraft = async (draft: BizAppDraftView) => {
    if (!draftValid(draft)) return;
    setBusy(`installDraft:${draft.id}`);
    try {
      const result = await installBizAppApi(request, { draftId: draft.id });
      toast.success(t("admin.installSuccess", { kind: result.kind }));
      await load();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("admin.fallbackInstall"));
    } finally {
      setBusy(null);
    }
  };

  /** Exports the template envelope JSON as a client-side download. */
  const onExportTemplate = async (template: BizAppTemplateView) => {
    const ref = templateRef(template);
    setBusy(`export:${ref}`);
    try {
      const envelope = await exportBizAppTemplateApi(request, ref);
      const blob = new Blob([JSON.stringify(envelope, null, 2)], { type: "application/json" });
      const downloadUrl = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = downloadUrl;
      anchor.download = `openvie-template-${template.kind}.json`;
      anchor.click();
      URL.revokeObjectURL(downloadUrl);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("admin.fallbackExport"));
    } finally {
      setBusy(null);
    }
  };

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
      case "draft": return target.name;
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
      } else if (target.action === "draft") {
        await deleteBizAppDraftApi(request, target.draftId);
        toast.success(t("admin.draftDeleted"));
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

  // -- AI draft generation (draft only, review before install) -----------------------------------

  const canSubmitImport = importMode === "json"
    ? (() => {
        if (!importContent.trim()) return false;
        try {
          JSON.parse(importContent);
          return true;
        } catch {
          return false;
        }
      })()
    : Boolean(importUrl.trim());

  const onGenerateDraft = async () => {
    if (!aiPrompt.trim() || aiSubmitting) return;
    setAiSubmitting(true);
    try {
      const result = await generateBizAppDraftApi(request, { prompt: aiPrompt.trim() });
      toast.success(t("admin.aiGenerated"));
      setAiSubmitting(false);
      setShowAiDialog(false);
      setAiPrompt("");
      router.push(`/admin/apps/compose/${result.draftId}`);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("admin.fallbackAi"));
      setAiSubmitting(false);
    }
  };

  const onImportTemplate = async () => {
    if (!canSubmitImport || importSubmitting) return;
    setImportSubmitting(true);
    try {
      const body = importMode === "json"
        ? { content: importContent }
        : { url: importUrl.trim(), sha256: importSha256.trim() || undefined };
      const draft = await importBizAppTemplateApi(request, body);
      toast.success(t("admin.importSuccess"));
      setImportSubmitting(false);
      setShowImportDialog(false);
      resetImportForm();
      router.push(`/admin/apps/compose/${draft.id}`);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("admin.fallbackImport"));
      setImportSubmitting(false);
    }
  };

  const onRemixTemplate = async () => {
    if (!remixTarget || remixSubmitting) return;
    const target = remixTarget;
    setRemixSubmitting(true);
    try {
      const draft = await remixBizAppTemplateApi(request, target.templateRef, remixSummary.trim() || undefined);
      toast.success(t("admin.remixSuccess", { name: target.name }));
      setRemixSubmitting(false);
      setRemixTarget(null);
      setRemixSummary("");
      router.push(`/admin/apps/compose/${draft.id}`);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("admin.fallbackRemix"));
      setRemixSubmitting(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
        <div className="min-w-0 flex-1">
          <h2 className="text-xl font-semibold text-foreground">{t("admin.title")}</h2>
          <p className="mt-1 text-sm text-muted-foreground">{t("admin.subtitle")}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2 lg:flex-nowrap lg:shrink-0">
          <Button size="sm" variant="outline" onClick={() => setShowAiDialog(true)}>
            <Sparkles className="mr-1 size-4" />{t("admin.newAiDraft")}
          </Button>
          <Button size="sm" variant="outline" onClick={() => setShowImportDialog(true)}>
            <Upload className="mr-1 size-4" />{t("admin.importTemplate")}
          </Button>
          <Button size="sm" onClick={() => router.push("/admin/apps/compose")}>
            <Plus className="mr-1 size-4" />{t("admin.newInCanvas")}
          </Button>
          <Button size="sm" variant="outline" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={cn("mr-1 size-4", loading && "animate-spin")} />{t("refresh")}
          </Button>
        </div>
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
                        <Button
                          size="sm" variant="outline"
                          disabled={Boolean(busy)}
                          onClick={() => { setRemixSummary(""); setRemixTarget({ templateRef: templateRef(template), name: templateName(template) }); }}
                        >
                          {t("admin.remix")}
                        </Button>
                        <Button
                          size="icon" variant="ghost" className="size-8"
                          aria-label={t("admin.export")}
                          disabled={busy === `export:${templateRef(template)}` || Boolean(busy)}
                          onClick={() => void onExportTemplate(template)}
                        >
                          {busy === `export:${templateRef(template)}` ? <Loader2 className="size-4 animate-spin" /> : <Download className="size-4" />}
                        </Button>
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

          {/* Drafts: AI/canvas/import/remix — never executable until installed */}
          <Card>
            <CardContent className="p-5">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <h3 className="font-medium">{t("admin.drafts")}</h3>
                  <p className="mt-0.5 text-xs text-muted-foreground">{t("admin.draftHint")}</p>
                </div>
              </div>
              {drafts.length === 0 ? (
                <p className="py-8 text-center text-sm text-muted-foreground">{t("admin.emptyDrafts")}</p>
              ) : (
                <div className="mt-3 space-y-2">
                  {drafts.map((draft) => (
                    <div key={draft.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border p-4">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <p className="truncate font-medium">{draftTitle(draft)}</p>
                          <Badge variant="outline">{draftSourceLabel(draft.source)}</Badge>
                        </div>
                        <p className="mt-1 text-xs text-muted-foreground">
                          {t("admin.draftUpdated", { time: format.dateTime(new Date(draft.updatedAt), { dateStyle: "medium" }) })}
                        </p>
                        {!draftValid(draft) && (
                          <p className="mt-1 text-xs text-muted-foreground">{t("admin.draftNotValid")}</p>
                        )}
                      </div>
                      <div className="flex shrink-0 items-center gap-2">
                        <Button
                          size="sm" variant="outline"
                          disabled={Boolean(busy)}
                          onClick={() => router.push(`/admin/apps/compose/${draft.id}`)}
                        >
                          {t("admin.draftEdit")}
                        </Button>
                        {draftValid(draft) && (
                          <Button
                            size="sm"
                            disabled={busy === `installDraft:${draft.id}` || Boolean(busy)}
                            onClick={() => void onInstallDraft(draft)}
                          >
                            {busy === `installDraft:${draft.id}` && <Loader2 className="mr-1 size-3.5 animate-spin" />}
                            {t("admin.install")}
                          </Button>
                        )}
                        <Button
                          size="icon" variant="ghost" className="size-8 text-destructive"
                          aria-label={t("admin.deleteDraft")}
                          disabled={Boolean(busy)}
                          onClick={() => setConfirmTarget({ action: "draft", draftId: draft.id, name: draftTitle(draft) })}
                        >
                          <Trash2 className="size-4" />
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

      {/* AI draft dialog: draft only — never installs automatically */}
      <Dialog open={showAiDialog} onOpenChange={setShowAiDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("admin.aiTitle")}</DialogTitle>
            <DialogDescription>{t("admin.aiDescription")}</DialogDescription>
          </DialogHeader>
          <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void onGenerateDraft(); }}>
            <div className="space-y-1.5">
              <Label htmlFor="bizappAiPrompt">{t("admin.aiPrompt")}</Label>
              <Textarea
                id="bizappAiPrompt"
                value={aiPrompt}
                maxLength={2000}
                className="min-h-28"
                onChange={(event) => setAiPrompt(event.target.value)}
              />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" disabled={aiSubmitting} onClick={() => setShowAiDialog(false)}>
                {t("cancel")}
              </Button>
              <Button type="submit" disabled={aiSubmitting || !aiPrompt.trim()}>
                {aiSubmitting && <Loader2 className="mr-1 size-4 animate-spin" />}
                {t("admin.aiGenerate")}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Template import dialog: envelope JSON or a catalog URL + expected hash */}
      <Dialog open={showImportDialog} onOpenChange={setShowImportDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("admin.importTitle")}</DialogTitle>
            <DialogDescription>{t("admin.importDescription")}</DialogDescription>
          </DialogHeader>
          <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void onImportTemplate(); }}>
            <div className="space-y-1.5">
              <Label htmlFor="bizappImportMode">{t("admin.importMode")}</Label>
              <Select value={importMode} onValueChange={(value) => setImportMode(value as "json" | "url")}>
                <SelectTrigger id="bizappImportMode">
                  <SelectValue>{importMode === "json" ? t("admin.importModeJson") : t("admin.importModeUrl")}</SelectValue>
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="json">{t("admin.importModeJson")}</SelectItem>
                  <SelectItem value="url">{t("admin.importModeUrl")}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {importMode === "json" ? (
              <div className="space-y-1.5">
                <Label htmlFor="bizappImportJson">{t("admin.importJson")}</Label>
                <Textarea
                  id="bizappImportJson"
                  value={importContent}
                  className="min-h-32 font-mono text-xs"
                  spellCheck={false}
                  onChange={(event) => setImportContent(event.target.value)}
                />
              </div>
            ) : (
              <>
                <div className="space-y-1.5">
                  <Label htmlFor="bizappImportUrl">{t("admin.importUrl")}</Label>
                  <Input
                    id="bizappImportUrl"
                    type="url"
                    value={importUrl}
                    onChange={(event) => setImportUrl(event.target.value)}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="bizappImportSha">{t("admin.importSha")}</Label>
                  <Input
                    id="bizappImportSha"
                    value={importSha256}
                    placeholder="SHA-256"
                    onChange={(event) => setImportSha256(event.target.value)}
                  />
                  <p className="text-xs text-muted-foreground">{t("admin.importHint")}</p>
                </div>
              </>
            )}
            <DialogFooter>
              <Button type="button" variant="outline" disabled={importSubmitting} onClick={() => setShowImportDialog(false)}>
                {t("cancel")}
              </Button>
              <Button type="submit" disabled={importSubmitting || !canSubmitImport}>
                {importSubmitting && <Loader2 className="mr-1 size-4 animate-spin" />}
                {t("admin.importSubmit")}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Remix dialog: copy to draft, keep upstream attribution, append the change */}
      <Dialog open={remixTarget !== null} onOpenChange={(open) => { if (!open && !remixSubmitting) setRemixTarget(null); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("admin.remixTitle")}</DialogTitle>
            <DialogDescription>
              {remixTarget && t("admin.remixDescription", { name: remixTarget.name })}
            </DialogDescription>
          </DialogHeader>
          <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void onRemixTemplate(); }}>
            <div className="space-y-1.5">
              <Label htmlFor="bizappRemixSummary">{t("admin.remixSummary")}</Label>
              <Input
                id="bizappRemixSummary"
                value={remixSummary}
                maxLength={200}
                onChange={(event) => setRemixSummary(event.target.value)}
              />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" disabled={remixSubmitting} onClick={() => setRemixTarget(null)}>
                {t("cancel")}
              </Button>
              <Button type="submit" disabled={remixSubmitting}>
                {remixSubmitting && <Loader2 className="mr-1 size-4 animate-spin" />}
                {t("admin.remixSubmit")}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
