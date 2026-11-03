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
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import toast from "react-hot-toast";
import { AlertCircle, Copy, KeyRound, Loader2, Plus, ShieldAlert } from "lucide-react";
import { useApiClient } from "@/hooks/useApiClient";
import { createApiKeyApi, listApiKeysApi, revokeApiKeyApi } from "@/lib/apikeys-api";
import { listWorkspacesApi } from "@/lib/workspace-api";
import type { ApiKeyView, CreatedApiKey, WorkspaceView } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ListPager, usePagedList } from "@/components/ui/list-pager";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Card, CardContent } from "@/components/ui/card";

type CreateKeyForm = { name: string; workspaceId?: string; expiresAt?: string };

function KeyStatus({ status, expired }: { status: string; expired: boolean }) {
  const t = useTranslations("ApiKeys");
  const usable = status === "ACTIVE" && !expired;
  return <span className={`ov-member-status ${usable ? "ov-member-status-active" : "ov-member-status-inactive"}`}><i aria-hidden="true" />{usable ? t("statuses.active") : t("statuses.inactive")}</span>;
}

function RoleLabel({ role }: { role: string }) {
  const t = useTranslations("ApiKeys");
  return <span className="text-sm font-medium">{role === "WORKSPACE_ADMIN" ? t("roles.WORKSPACE_ADMIN") : t("roles.MEMBER")}</span>;
}

function TableSkeleton() {
  return <div className="space-y-4 p-5">{Array.from({ length: 3 }).map((_, index) => (
    <div key={index} className="flex items-center gap-4"><Skeleton className="size-8 rounded-full" /><Skeleton className="h-5 flex-1" /><Skeleton className="h-5 w-28" /><Skeleton className="h-5 w-24" /></div>
  ))}</div>;
}

/** True when the given datetime string is parseable and already in the past. */
function isPastInstant(raw: string): boolean {
  const when = new Date(raw);
  return !Number.isNaN(when.getTime()) && when.getTime() <= Date.now();
}

/**
 * Admin panel: machine API keys of the organization (ORG_OWNER).
 *
 * The key authenticates as a real service member inside the workspace bound at
 * creation. The plaintext secret (`ovk_...`) is shown exactly once after the
 * create call — later calls only ever see the masked prefix. Every call goes
 * through `/api/v1/admin/api-keys/*` (see docs/INTEGRATION.md §2).
 */
export default function ApiKeysPage() {
  const t = useTranslations("ApiKeys");
  const format = useFormatter();
  const { request } = useApiClient();
  const [keys, setKeys] = useState<ApiKeyView[]>([]);
  const [workspaces, setWorkspaces] = useState<WorkspaceView[]>([]);
  const pagedKeys = usePagedList(keys);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [createdKey, setCreatedKey] = useState<CreatedApiKey | null>(null);
  const [copied, setCopied] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<ApiKeyView | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  // Role and workspace live as controlled state (Select inputs), matching the
  // workspaces admin page instead of RHF `watch` inside JSX.
  const [formWorkspaceId, setFormWorkspaceId] = useState("");
  const [formRole, setFormRole] = useState<"MEMBER" | "WORKSPACE_ADMIN">("MEMBER");

  const formatDate = (value: string) => format.dateTime(new Date(value), { dateStyle: "medium" });
  const formatUsage = (value: string | null) => (
    value ? format.dateTime(new Date(value), { dateStyle: "medium", timeStyle: "short" }) : t("neverUsed")
  );
  const workspaceName = (workspaceId: string) =>
    workspaces.find(workspace => workspace.id === workspaceId)?.name ?? workspaceId;

  const loadKeys = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [nextKeys, nextWorkspaces] = await Promise.all([
        listApiKeysApi(request),
        listWorkspacesApi(request),
      ]);
      setKeys(nextKeys);
      setWorkspaces(nextWorkspaces);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("fallback.load"));
    } finally {
      setLoading(false);
    }
  }, [request, t]);

  useEffect(() => {
    const task = window.setTimeout(() => { void loadKeys(); }, 0);
    return () => window.clearTimeout(task);
  }, [loadKeys]);

  const createSchema = z.object({
    name: z.string().trim().min(1, t("validName")).max(120, t("validName")),
    expiresAt: z.string().optional(),
  });
  const createForm = useForm<CreateKeyForm>({ resolver: zodResolver(createSchema) });

  const onCreateKey = async (values: CreateKeyForm) => {
    let expiry: string | undefined;
    const rawExpiry = values.expiresAt?.trim() ?? "";
    if (rawExpiry) {
      const when = new Date(rawExpiry);
      if (Number.isNaN(when.getTime())) {
        createForm.setError("expiresAt", { type: "manual", message: t("validExpiry") });
        return;
      }
      if (isPastInstant(rawExpiry)) {
        createForm.setError("expiresAt", { type: "manual", message: t("validExpiryFuture") });
        return;
      }
      expiry = when.toISOString();
    }
    if (!formWorkspaceId) {
      createForm.setError("workspaceId", { type: "manual", message: t("validWorkspace") });
      return;
    }
    try {
      const created = await createApiKeyApi(request, {
        name: values.name,
        workspaceId: formWorkspaceId,
        role: formRole,
        expiresAt: expiry,
      });
      toast.success(t("createdToast", { name: created.keyView.name }));
      createForm.reset();
      setShowCreate(false);
      setCopied(false);
      setCreatedKey(created);
      await loadKeys();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("fallback.create"));
    }
  };

  const onRevoke = async (key: ApiKeyView) => {
    setBusyId(key.id);
    try {
      await revokeApiKeyApi(request, key.id);
      toast.success(t("revokedToast", { name: key.name }));
      setRevokeTarget(null);
      await loadKeys();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("fallback.revoke"));
    } finally {
      setBusyId(null);
    }
  };

  const copySecret = async () => {
    if (!createdKey) return;
    try {
      await navigator.clipboard.writeText(createdKey.key);
      setCopied(true);
    } catch {
      toast.error(t("copyFailed"));
    }
  };

  const usable = (key: ApiKeyView) => key.status === "ACTIVE" && !key.expired;

  return <div className="space-y-6">
    <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0 flex-1">
        <h2 className="text-xl font-semibold text-foreground">{t("title")}</h2>
        <p className="mt-1 text-sm text-muted-foreground">{t("description")}</p>
      </div>
      <div className="shrink-0">
        <Button
          size="sm"
          onClick={() => {
            setShowCreate(true);
            setFormWorkspaceId("");
            setFormRole("MEMBER");
            createForm.reset({ name: "", expiresAt: "" });
          }}
          className="gap-1.5"
        >
          <Plus className="size-4" />
          {t("create")}
        </Button>
      </div>
    </div>

    {error && (
      <div role="alert" className="flex items-center justify-between rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
        <span className="flex items-center gap-2"><AlertCircle className="size-4" />{error}</span>
        <Button size="sm" variant="outline" onClick={() => void loadKeys()}>{t("retry")}</Button>
      </div>
    )}

    <Card>
      {loading ? (
        <TableSkeleton />
      ) : keys.length === 0 ? (
        <CardContent className="py-16 text-center">
          <KeyRound className="mx-auto mb-3 size-10 text-muted-foreground opacity-50" />
          <p className="font-medium text-muted-foreground">{t("empty")}</p>
          <p className="mt-1 text-sm text-muted-foreground">{t("emptyHint")}</p>
        </CardContent>
      ) : (
        <>
          <div className="divide-y divide-border sm:hidden">
            {pagedKeys.slice.map(key => (
              <div key={`api-key-mobile-${key.id}`} className="p-3">
                <div className="flex items-center gap-3">
                  <span className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary/15 text-xs text-primary"><KeyRound className="size-4" /></span>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium text-foreground">{key.name}</p>
                    <p className="truncate font-mono text-xs text-muted-foreground">{key.keyPrefix}…</p>
                  </div>
                  <KeyStatus status={key.status} expired={key.expired} />
                </div>
                <p className="mt-1 text-xs text-muted-foreground">
                  {workspaceName(key.workspaceId)} · <RoleLabel role={key.role} />
                </p>
                <p className="mt-1 text-xs text-muted-foreground">
                  {formatUsage(key.lastUsedAt)} · {formatDate(key.createdAt)}
                </p>
                <div className="mt-2.5 flex justify-end">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busyId === key.id || !usable(key)}
                    onClick={() => setRevokeTarget(key)}
                  >
                    {t("revoke")}
                  </Button>
                </div>
              </div>
            ))}
          </div>
          <div className="hidden sm:block">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("table.key")}</TableHead>
                  <TableHead>{t("table.workspace")}</TableHead>
                  <TableHead>{t("table.role")}</TableHead>
                  <TableHead>{t("table.status")}</TableHead>
                  <TableHead className="hidden sm:table-cell">{t("table.usage")}</TableHead>
                  <TableHead className="text-right">{t("table.actions")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {pagedKeys.slice.map(key => (
                  <TableRow key={`api-key-${key.id}`}>
                    <TableCell>
                      <div className="min-w-0">
                        <p className="truncate text-sm font-medium text-foreground">{key.name}</p>
                        <p className="font-mono text-xs text-muted-foreground">{key.keyPrefix}…</p>
                      </div>
                    </TableCell>
                    <TableCell className="text-sm text-muted-foreground">{workspaceName(key.workspaceId)}</TableCell>
                    <TableCell><RoleLabel role={key.role} /></TableCell>
                    <TableCell><KeyStatus status={key.status} expired={key.expired} /></TableCell>
                    <TableCell className="hidden text-sm text-muted-foreground sm:table-cell">
                      {formatUsage(key.lastUsedAt)}
                    </TableCell>
                    <TableCell className="text-right">
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busyId === key.id || !usable(key)}
                        onClick={() => setRevokeTarget(key)}
                      >
                        {t("revoke")}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <ListPager
            page={pagedKeys.page}
            pageCount={pagedKeys.pageCount}
            size={pagedKeys.size}
            total={pagedKeys.total}
            onPageChange={pagedKeys.setPage}
            onSizeChange={(nextSize) => { pagedKeys.setSize(nextSize); pagedKeys.setPage(1); }}
          />
        </>
      )}
    </Card>

    {/* Create API key dialog */}
    <Dialog open={showCreate} onOpenChange={setShowCreate}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("createTitle")}</DialogTitle>
          <DialogDescription>{t("createDescription")}</DialogDescription>
        </DialogHeader>
        <form onSubmit={createForm.handleSubmit(onCreateKey)} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="apiKeyName">{t("name")}</Label>
            <Input
              id="apiKeyName"
              placeholder={t("namePlaceholder")}
              maxLength={120}
              {...createForm.register("name")}
            />
            {createForm.formState.errors.name && (
              <p className="text-xs text-destructive">{createForm.formState.errors.name.message}</p>
            )}
          </div>
          <div className="space-y-1.5">
            <Label>{t("workspace")}</Label>
            <Select
              value={formWorkspaceId}
              onValueChange={value => {
                if (value != null) setFormWorkspaceId(value);
                if (value) createForm.clearErrors("workspaceId");
              }}
            >
              <SelectTrigger><SelectValue placeholder={t("workspacePlaceholder")} /></SelectTrigger>
              <SelectContent>
                {workspaces.filter(workspace => workspace.status !== "ARCHIVED").map(workspace => (
                  <SelectItem key={workspace.id} value={workspace.id}>
                    {workspace.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">{t("workspaceHint")}</p>
            {createForm.formState.errors.workspaceId && (
              <p className="text-xs text-destructive">{createForm.formState.errors.workspaceId.message}</p>
            )}
          </div>
          <div className="space-y-1.5">
            <Label>{t("role")}</Label>
            <Select
              value={formRole}
              onValueChange={value => {
                if (value != null) setFormRole(value as "MEMBER" | "WORKSPACE_ADMIN");
              }}
            >
              <SelectTrigger><SelectValue>{formRole === "WORKSPACE_ADMIN" ? t("roles.WORKSPACE_ADMIN") : t("roles.MEMBER")}</SelectValue></SelectTrigger>
              <SelectContent>
                <SelectItem value="MEMBER">{t("roles.MEMBER")}</SelectItem>
                <SelectItem value="WORKSPACE_ADMIN">{t("roles.WORKSPACE_ADMIN")}</SelectItem>
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">{t("roleHint")}</p>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="apiKeyExpiry">{t("expiry")}</Label>
            <Input id="apiKeyExpiry" type="datetime-local" {...createForm.register("expiresAt")} />
            <p className="text-xs text-muted-foreground">{t("expiryHint")}</p>
            {createForm.formState.errors.expiresAt && (
              <p className="text-xs text-destructive">{createForm.formState.errors.expiresAt.message}</p>
            )}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setShowCreate(false)}>{t("cancel")}</Button>
            <Button type="submit" disabled={createForm.formState.isSubmitting}>
              {createForm.formState.isSubmitting ? <Loader2 className="size-4 animate-spin" /> : t("createAction")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    {/* One-time secret display */}
    <Dialog open={createdKey !== null} onOpenChange={open => { if (!open && copied) setCreatedKey(null); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><KeyRound className="size-4" />{t("createdTitle")}</DialogTitle>
          <DialogDescription>{t("createdDescription")}</DialogDescription>
        </DialogHeader>
        <div className="flex items-center gap-2">
          <code className="min-w-0 flex-1 truncate rounded-md bg-muted px-3 py-2 font-mono text-sm" aria-label={t("secretLabel")}>
            {createdKey?.key}
          </code>
          <Button size="sm" variant="outline" onClick={() => void copySecret()} className="gap-1.5">
            <Copy className="size-3.5" />
            {copied ? t("copied") : t("copy")}
          </Button>
        </div>
        <div role="alert" className="flex items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 p-3 text-sm text-amber-700 dark:text-amber-400">
          <ShieldAlert className="mt-0.5 size-4 shrink-0" />
          <span>{t("createdWarning")}</span>
        </div>
        <DialogFooter>
          <Button type="button" onClick={() => { setCreatedKey(null); setCopied(false); }} disabled={!copied}>
            {t("acknowledge")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    {/* Revoke confirmation dialog */}
    <Dialog open={revokeTarget !== null} onOpenChange={open => { if (!open && !busyId) setRevokeTarget(null); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("revoke")}</DialogTitle>
          <DialogDescription>
            {revokeTarget && t("revokeDescription", { name: revokeTarget.name })}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button type="button" variant="outline" disabled={Boolean(busyId)} onClick={() => setRevokeTarget(null)}>
            {t("cancel")}
          </Button>
          <Button type="button" variant="destructive" disabled={Boolean(busyId)} onClick={() => {
            if (revokeTarget) void onRevoke(revokeTarget);
          }}>
            {busyId === revokeTarget?.id ? <Loader2 className="size-4 animate-spin" /> : t("revoke")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>;
}
