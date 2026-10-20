"use client";

import { useCallback, useEffect, useState } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import toast from "react-hot-toast";
import { Archive, AlertCircle, Building2, Eye, EyeOff, Loader2, Pencil, Plus, RefreshCw } from "lucide-react";
import { useApiClient } from "@/hooks/useApiClient";
import { useAuthStore } from "@/components/providers/StoreProvider";
import {
  archiveWorkspaceApi,
  createWorkspaceApi,
  listWorkspacesApi,
  renameWorkspaceApi,
  setWorkspaceVisibilityApi,
} from "@/lib/workspace-api";
import type { WorkspaceView, WorkspaceVisibility } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ListPager, usePagedList } from "@/components/ui/list-pager";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { announceWorkspacesChanged } from "@/lib/workspace-events";

export default function WorkspacesPage() {
  const t = useTranslations("Workspaces");
  const format = useFormatter();
  const { request } = useApiClient();
  const user = useAuthStore((s) => s.user);
  const [workspaces, setWorkspaces] = useState<WorkspaceView[]>([]);
  const pagedWorkspaces = usePagedList(workspaces);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [renameTarget, setRenameTarget] = useState<WorkspaceView | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [archiveTarget, setArchiveTarget] = useState<WorkspaceView | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  // The visibility select is state-driven so its trigger shows the translated
  // label instead of the raw enum ("PUBLIC").
  const [newVisibility, setNewVisibility] = useState<WorkspaceVisibility>("PUBLIC");

  const schema = z.object({
    name: z.string().trim().min(1, t("validation.nameRequired")).max(255),
    visibility: z.enum(["PUBLIC", "PRIVATE"]),
  });
  type CreateForm = z.infer<typeof schema>;
  const { register, handleSubmit, setValue, reset, formState: { errors, isSubmitting } } =
    useForm<CreateForm>({ resolver: zodResolver(schema), defaultValues: { visibility: "PUBLIC" } });

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setWorkspaces(await listWorkspacesApi(request));
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

  const onCreate = async (values: CreateForm) => {
    try {
      await createWorkspaceApi(request, values.name, values.visibility);
      announceWorkspacesChanged();
      toast.success(t("created", { name: values.name }));
      reset(); setShowCreate(false); await load();
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.create")); }
  };

  const openRename = (workspace: WorkspaceView) => {
    setRenameTarget(workspace);
    setRenameValue(workspace.name);
  };

  const onConfirmRename = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!renameTarget) return;
    const name = renameValue.trim();
    if (!name || name === renameTarget.name) {
      setRenameTarget(null);
      return;
    }
    setBusyId(renameTarget.id);
    try {
      const updated = await renameWorkspaceApi(request, renameTarget.id, name);
      setWorkspaces(current => current.map(item => item.id === updated.id ? updated : item));
      announceWorkspacesChanged();
      toast.success(t("renamed"));
      setRenameTarget(null);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("fallback.rename"));
    } finally {
      setBusyId(null);
    }
  };

  const toggleVisibility = async (workspace: WorkspaceView) => {
    const next: WorkspaceVisibility = workspace.visibility === "PUBLIC" ? "PRIVATE" : "PUBLIC";
    setBusyId(workspace.id);
    try {
      const updated = await setWorkspaceVisibilityApi(request, workspace.id, next);
      setWorkspaces(current => current.map(item => item.id === updated.id ? updated : item));
      announceWorkspacesChanged();
      toast.success(t("visibilityUpdated", { visibility: next === "PUBLIC" ? t("public") : t("private") }));
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.visibility")); }
    finally { setBusyId(null); }
  };

  const openArchive = (workspace: WorkspaceView) => {
    setArchiveTarget(workspace);
  };

  const onConfirmArchive = async () => {
    if (!archiveTarget) return;
    setBusyId(archiveTarget.id);
    try {
      await archiveWorkspaceApi(request, archiveTarget.id);
      announceWorkspacesChanged();
      toast.success(t("archived", { name: archiveTarget.name }));
      setArchiveTarget(null);
      await load();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("fallback.archive"));
    } finally {
      setBusyId(null);
    }
  };

  const formatDate = (value: string) => format.dateTime(new Date(value), { dateStyle: "medium" });

  return <div className="space-y-6">
    <div className="flex items-center justify-between gap-4">
      <div><h2 className="text-xl font-semibold text-foreground">{t("title")}</h2><p className="mt-1 text-sm text-muted-foreground">{t("description")}</p></div>
      <div className="flex gap-2">
        <Button size="sm" variant="outline" onClick={() => void load()}><RefreshCw className="mr-1 size-4" />{t("refresh")}</Button>
        <Button size="sm" onClick={() => { setShowCreate(true); reset(); setNewVisibility("PUBLIC"); }} className="gap-1.5"><Plus className="size-4" />{t("create")}</Button>
      </div>
    </div>

    {error && <div role="alert" className="flex items-center justify-between rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive"><span className="flex items-center gap-2"><AlertCircle className="size-4" />{error}</span><Button size="sm" variant="outline" onClick={() => void load()}>{t("retry")}</Button></div>}

    <Card>{loading ? <div className="space-y-4 p-5">{Array.from({ length: 3 }).map((_, index) => <div key={index} className="flex items-center gap-4"><Skeleton className="size-8 rounded-full" /><Skeleton className="h-5 flex-1" /><Skeleton className="h-5 w-28" /></div>)}</div> : workspaces.length === 0 ? <CardContent className="py-16 text-center"><Building2 className="mx-auto mb-3 size-10 text-muted-foreground opacity-50" /><p className="font-medium text-muted-foreground">{t("empty")}</p></CardContent> : <>
      <div className="divide-y divide-border sm:hidden">
        {pagedWorkspaces.slice.map(workspace => (
          <div key={`workspace-mobile-${workspace.id}`} className="p-3">
            <div className="flex items-center justify-between gap-2">
              <p className="min-w-0 truncate text-sm font-medium text-foreground">{workspace.name}</p>
              <div className="flex shrink-0 items-center gap-1.5">
                {workspace.isDefault && <Badge variant="secondary">{t("default")}</Badge>}
                {workspace.status === "ARCHIVED" && <Badge variant="secondary">{t("archivedBadge")}</Badge>}
                <Badge variant={workspace.visibility === "PUBLIC" ? "default" : "outline"}>
                  {workspace.visibility === "PUBLIC" ? t("public") : t("private")}
                </Badge>
              </div>
            </div>
            <p className="mt-0.5 truncate text-xs text-muted-foreground">
              {formatDate(workspace.createdAt)} · {workspace.memberCount} {t("table.members").toLowerCase()}
            </p>
            <div className="mt-2.5 flex flex-wrap justify-end gap-2">
              <Button size="sm" variant="outline" disabled={busyId === workspace.id} onClick={() => openRename(workspace)}>
                <Pencil className="size-3.5" />
                {t("rename")}
              </Button>
              <Button size="sm" variant="outline" disabled={busyId === workspace.id} title={workspace.visibility === "PUBLIC" ? t("makePrivate") : t("makePublic")} onClick={() => void toggleVisibility(workspace)}>
                {workspace.visibility === "PUBLIC" ? <EyeOff className="size-3.5" /> : <Eye className="size-3.5" />}
                {workspace.visibility === "PUBLIC" ? t("private") : t("public")}
              </Button>
              {!workspace.isDefault && workspace.status !== "ARCHIVED" && (
                <Button size="sm" variant="outline" disabled={busyId === workspace.id || workspace.id === user?.activeWorkspaceId} title={t("archive")} onClick={() => openArchive(workspace)}>
                  <Archive className="size-3.5" />
                  {t("archive")}
                </Button>
              )}
            </div>
          </div>
        ))}
      </div>
      <div className="hidden sm:block">
      <Table>
      <TableHeader><TableRow><TableHead>{t("table.name")}</TableHead><TableHead className="hidden sm:table-cell">{t("table.slug")}</TableHead><TableHead>{t("table.visibility")}</TableHead><TableHead>{t("table.members")}</TableHead><TableHead className="hidden sm:table-cell">{t("table.created")}</TableHead><TableHead className="text-right">{t("table.actions")}</TableHead></TableRow></TableHeader>
      <TableBody>
        {pagedWorkspaces.slice.map(workspace => <TableRow key={workspace.id}>
          <TableCell>
            <div className="flex items-center gap-2">
              <span className="max-w-40 truncate font-medium text-foreground sm:max-w-none">{workspace.name}</span>
              {workspace.isDefault && <Badge variant="secondary" className="ml-2">{t("default")}</Badge>}
              {workspace.status === "ARCHIVED" && <Badge variant="secondary" className="ml-2">{t("archivedBadge")}</Badge>}
            </div>
          </TableCell>
          <TableCell className="hidden text-sm text-muted-foreground sm:table-cell">{workspace.slug}</TableCell>
          <TableCell><Badge variant={workspace.visibility === "PUBLIC" ? "default" : "outline"}>{workspace.visibility === "PUBLIC" ? t("public") : t("private")}</Badge></TableCell>
          <TableCell className="text-sm text-muted-foreground">{workspace.memberCount}</TableCell>
          <TableCell className="hidden text-sm text-muted-foreground sm:table-cell">{formatDate(workspace.createdAt)}</TableCell>
          <TableCell className="space-x-2 text-right">
            <Button size="sm" variant="outline" disabled={busyId === workspace.id} onClick={() => openRename(workspace)}>
              <Pencil className="size-3.5" />
              {t("rename")}
            </Button>
            <Button size="sm" variant="outline" disabled={busyId === workspace.id} title={workspace.visibility === "PUBLIC" ? t("makePrivate") : t("makePublic")} onClick={() => void toggleVisibility(workspace)}>
              {workspace.visibility === "PUBLIC" ? <EyeOff className="size-3.5" /> : <Eye className="size-3.5" />}
              {workspace.visibility === "PUBLIC" ? t("private") : t("public")}
            </Button>
            {!workspace.isDefault && workspace.status !== "ARCHIVED" && <Button size="sm" variant="outline" disabled={busyId === workspace.id || workspace.id === user?.activeWorkspaceId} title={t("archive")} onClick={() => openArchive(workspace)}><Archive className="size-3.5" />{t("archive")}</Button>}
          </TableCell>
        </TableRow>)}
      </TableBody>
    </Table>
      </div>
      <ListPager
        page={pagedWorkspaces.page}
        pageCount={pagedWorkspaces.pageCount}
        size={pagedWorkspaces.size}
        total={pagedWorkspaces.total}
        onPageChange={pagedWorkspaces.setPage}
        onSizeChange={(nextSize) => { pagedWorkspaces.setSize(nextSize); pagedWorkspaces.setPage(1); }}
      />
    </>}</Card>

    <Dialog open={showCreate} onOpenChange={setShowCreate}><DialogContent><DialogHeader><DialogTitle>{t("createTitle")}</DialogTitle></DialogHeader><form onSubmit={handleSubmit(onCreate)} className="space-y-4"><div className="space-y-1.5"><Label htmlFor="workspaceName">{t("name")}</Label><Input id="workspaceName" placeholder={t("namePlaceholder")} {...register("name")} />{errors.name && <p className="text-xs text-destructive">{errors.name.message}</p>}</div><div className="space-y-1.5"><Label>{t("visibility")}</Label><Select value={newVisibility} onValueChange={value => { setNewVisibility(value as WorkspaceVisibility); setValue("visibility", value as WorkspaceVisibility); }}><SelectTrigger><SelectValue>{newVisibility === "PUBLIC" ? t("public") : t("private")}</SelectValue></SelectTrigger><SelectContent><SelectItem value="PUBLIC">{t("public")}</SelectItem><SelectItem value="PRIVATE">{t("private")}</SelectItem></SelectContent></Select><p className="text-xs text-muted-foreground">{t("visibilityHint")}</p></div><DialogFooter><Button type="submit" disabled={isSubmitting}>{isSubmitting ? t("creating") : t("create")}</Button></DialogFooter></form></DialogContent></Dialog>

    {/* Rename Workspace Dialog */}
    <Dialog open={renameTarget !== null} onOpenChange={(open) => { if (!open && !busyId) setRenameTarget(null); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("rename")}</DialogTitle>
          <DialogDescription>
            {renameTarget && t("renamePrompt", { name: renameTarget.name })}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={onConfirmRename} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="renameInput">{t("name")}</Label>
            <Input
              id="renameInput"
              autoFocus
              value={renameValue}
              onChange={(e) => setRenameValue(e.target.value)}
              placeholder={t("namePlaceholder")}
            />
          </div>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={Boolean(busyId)}
              onClick={() => setRenameTarget(null)}
            >
              {t("cancel")}
            </Button>
            <Button
              type="submit"
              disabled={Boolean(busyId) || !renameValue.trim()}
            >
              {busyId === renameTarget?.id ? <Loader2 className="size-4 animate-spin" /> : t("save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    {/* Archive Workspace Dialog */}
    <Dialog open={archiveTarget !== null} onOpenChange={(open) => { if (!open && !busyId) setArchiveTarget(null); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("archive")}</DialogTitle>
          <DialogDescription>
            {archiveTarget && t("confirmArchive", { name: archiveTarget.name })}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={Boolean(busyId)}
            onClick={() => setArchiveTarget(null)}
          >
            {t("cancel")}
          </Button>
          <Button
            type="button"
            variant="destructive"
            disabled={Boolean(busyId)}
            onClick={() => void onConfirmArchive()}
          >
            {busyId === archiveTarget?.id ? <Loader2 className="size-4 animate-spin" /> : t("archive")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>;
}
