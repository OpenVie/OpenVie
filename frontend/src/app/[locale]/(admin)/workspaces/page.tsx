"use client";

import { useCallback, useEffect, useState } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import toast from "react-hot-toast";
import { Archive, AlertCircle, Building2, Eye, EyeOff, Plus, RefreshCw } from "lucide-react";
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
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

export default function WorkspacesPage() {
  const t = useTranslations("Workspaces");
  const format = useFormatter();
  const { request } = useApiClient();
  const user = useAuthStore((s) => s.user);
  const [workspaces, setWorkspaces] = useState<WorkspaceView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);

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
      toast.success(t("created", { name: values.name }));
      reset(); setShowCreate(false); await load();
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.create")); }
  };

  const rename = async (workspace: WorkspaceView) => {
    const name = window.prompt(t("renamePrompt", { name: workspace.name }), workspace.name);
    if (!name || name.trim() === workspace.name) return;
    setBusyId(workspace.id);
    try {
      const updated = await renameWorkspaceApi(request, workspace.id, name.trim());
      setWorkspaces(current => current.map(item => item.id === updated.id ? updated : item));
      toast.success(t("renamed"));
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.rename")); }
    finally { setBusyId(null); }
  };

  const toggleVisibility = async (workspace: WorkspaceView) => {
    const next: WorkspaceVisibility = workspace.visibility === "PUBLIC" ? "PRIVATE" : "PUBLIC";
    setBusyId(workspace.id);
    try {
      const updated = await setWorkspaceVisibilityApi(request, workspace.id, next);
      setWorkspaces(current => current.map(item => item.id === updated.id ? updated : item));
      toast.success(t("visibilityUpdated", { visibility: next === "PUBLIC" ? t("public") : t("private") }));
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.visibility")); }
    finally { setBusyId(null); }
  };

  const archive = async (workspace: WorkspaceView) => {
    if (!window.confirm(t("confirmArchive", { name: workspace.name }))) return;
    setBusyId(workspace.id);
    try {
      await archiveWorkspaceApi(request, workspace.id);
      toast.success(t("archived", { name: workspace.name }));
      await load();
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.archive")); }
    finally { setBusyId(null); }
  };

  const formatDate = (value: string) => format.dateTime(new Date(value), { dateStyle: "medium" });

  return <div className="space-y-6">
    <div className="flex items-center justify-between gap-4">
      <div><h2 className="text-xl font-semibold text-slate-800">{t("title")}</h2><p className="mt-1 text-sm text-slate-500">{t("description")}</p></div>
      <div className="flex gap-2">
        <Button size="sm" variant="outline" onClick={() => void load()}><RefreshCw className="mr-1 size-4" />{t("refresh")}</Button>
        <Button size="sm" onClick={() => setShowCreate(true)} className="gap-1.5 bg-indigo-600 text-white hover:bg-indigo-700"><Plus className="size-4" />{t("create")}</Button>
      </div>
    </div>

    {error && <div role="alert" className="flex items-center justify-between rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-800"><span className="flex items-center gap-2"><AlertCircle className="size-4" />{error}</span><Button size="sm" variant="outline" onClick={() => void load()}>{t("retry")}</Button></div>}

    <Card>{loading ? <div className="space-y-4 p-5">{Array.from({ length: 3 }).map((_, index) => <div key={index} className="flex items-center gap-4"><Skeleton className="size-8 rounded-full" /><Skeleton className="h-5 flex-1" /><Skeleton className="h-5 w-28" /></div>)}</div> : workspaces.length === 0 ? <CardContent className="py-16 text-center"><Building2 className="mx-auto mb-3 size-10 text-slate-300" /><p className="font-medium text-slate-500">{t("empty")}</p></CardContent> : <Table>
      <TableHeader><TableRow><TableHead>{t("table.name")}</TableHead><TableHead>{t("table.slug")}</TableHead><TableHead>{t("table.visibility")}</TableHead><TableHead>{t("table.members")}</TableHead><TableHead>{t("table.created")}</TableHead><TableHead className="text-right">{t("table.actions")}</TableHead></TableRow></TableHeader>
      <TableBody>
        {workspaces.map(workspace => <TableRow key={workspace.id}>
          <TableCell><span className="font-medium text-slate-800">{workspace.name}</span>{workspace.isDefault && <Badge variant="secondary" className="ml-2">{t("default")}</Badge>}{workspace.status === "ARCHIVED" && <Badge variant="secondary" className="ml-2">{t("archivedBadge")}</Badge>}</TableCell>
          <TableCell className="text-sm text-slate-500">{workspace.slug}</TableCell>
          <TableCell><Badge variant={workspace.visibility === "PUBLIC" ? "default" : "outline"}>{workspace.visibility === "PUBLIC" ? t("public") : t("private")}</Badge></TableCell>
          <TableCell className="text-sm text-slate-500">{workspace.memberCount}</TableCell>
          <TableCell className="text-sm text-slate-500">{formatDate(workspace.createdAt)}</TableCell>
          <TableCell className="space-x-2 text-right">
            <Button size="sm" variant="outline" disabled={busyId === workspace.id} onClick={() => void rename(workspace)}>{t("rename")}</Button>
            <Button size="sm" variant="outline" disabled={busyId === workspace.id} title={workspace.visibility === "PUBLIC" ? t("makePrivate") : t("makePublic")} onClick={() => void toggleVisibility(workspace)}>
              {workspace.visibility === "PUBLIC" ? <EyeOff className="mr-1 size-3.5" /> : <Eye className="mr-1 size-3.5" />}
              {workspace.visibility === "PUBLIC" ? t("private") : t("public")}
            </Button>
            {!workspace.isDefault && workspace.status !== "ARCHIVED" && <Button size="sm" variant="outline" disabled={busyId === workspace.id || workspace.id === user?.activeWorkspaceId} onClick={() => void archive(workspace)}><Archive className="mr-1 size-3.5" />{t("archive")}</Button>}
          </TableCell>
        </TableRow>)}
      </TableBody>
    </Table>}</Card>

    <Dialog open={showCreate} onOpenChange={setShowCreate}><DialogContent><DialogHeader><DialogTitle>{t("createTitle")}</DialogTitle></DialogHeader><form onSubmit={handleSubmit(onCreate)} className="space-y-4"><div className="space-y-1.5"><Label htmlFor="workspaceName">{t("name")}</Label><Input id="workspaceName" placeholder={t("namePlaceholder")} {...register("name")} />{errors.name && <p className="text-xs text-red-600">{errors.name.message}</p>}</div><div className="space-y-1.5"><Label>{t("visibility")}</Label><Select defaultValue="PUBLIC" onValueChange={value => setValue("visibility", value as WorkspaceVisibility)}><SelectTrigger><SelectValue /></SelectTrigger><SelectContent><SelectItem value="PUBLIC">{t("public")}</SelectItem><SelectItem value="PRIVATE">{t("private")}</SelectItem></SelectContent></Select><p className="text-xs text-slate-500">{t("visibilityHint")}</p></div><DialogFooter><Button type="submit" disabled={isSubmitting} className="bg-indigo-600 text-white hover:bg-indigo-700">{isSubmitting ? t("creating") : t("create")}</Button></DialogFooter></form></DialogContent></Dialog>
  </div>;
}
