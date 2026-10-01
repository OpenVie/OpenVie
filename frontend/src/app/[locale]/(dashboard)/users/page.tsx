"use client";


import { useCallback, useEffect, useMemo, useState } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import toast from "react-hot-toast";
import { AlertCircle, Mail, RefreshCw, UserPlus, Users } from "lucide-react";
import { useApiClient } from "@/hooks/useApiClient";
import { useAuthStore } from "@/components/providers/StoreProvider";
import {
  cancelTeamInvitation,
  getTeamDirectory,
  inviteTeamMember,
  resendTeamInvitation,
  updateTeamMemberRole,
  updateTeamMemberStatus,
} from "@/lib/users-api";
import type { TeamDirectory, TeamMember, UserRole, UserStatus } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Skeleton } from "@/components/ui/skeleton";
import { Card, CardContent } from "@/components/ui/card";

type InviteForm = { email: string; role: UserRole };
const initials = (name: string) => name.split(" ").filter(Boolean).map(part => part[0]).join("").toUpperCase().slice(0, 2) || "?";

function StatusBadge({ status }: { status: string }) {
  const t = useTranslations("Users");
  const active = status === "ACTIVE";
  const pending = status === "PENDING";
  return <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${
    active ? "bg-green-100 text-green-800" : pending ? "bg-amber-100 text-amber-800" : "bg-slate-100 text-slate-600"
  }`}>{status === "ACTIVE" ? t("statuses.active") : status === "PENDING" ? t("statuses.pending") : t("statuses.inactive")}</span>;
}

function TableSkeleton() {
  return <div className="space-y-4 p-5">{Array.from({ length: 4 }).map((_, index) => (
    <div key={index} className="flex items-center gap-4"><Skeleton className="size-8 rounded-full" /><Skeleton className="h-5 flex-1" /><Skeleton className="h-5 w-28" /><Skeleton className="h-5 w-24" /></div>
  ))}</div>;
}

export default function UsersPage() {
  const t = useTranslations("Users");
  const format = useFormatter();
  const { request } = useApiClient();
  const authUser = useAuthStore(state => state.user);
  const [directory, setDirectory] = useState<TeamDirectory>({ members: [], invitations: [] });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showInvite, setShowInvite] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const isAdmin = authUser?.role === "TENANT_ADMIN";
  const activeAdminCount = useMemo(() => directory.members.filter(member => member.role === "TENANT_ADMIN" && member.status === "ACTIVE").length, [directory.members]);
  const inviteSchema = z.object({ email: z.string().email(t("validEmail")), role: z.enum(["TENANT_ADMIN", "USER"]) });
  const roleLabel = (role: UserRole) => role === "TENANT_ADMIN" ? t("roles.admin") : t("roles.user");
  const formatDate = (value: string) => format.dateTime(new Date(value), { dateStyle: "medium" });

  const loadDirectory = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setDirectory(await getTeamDirectory(request));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("fallback.load"));
    } finally {
      setLoading(false);
    }
  }, [request, t]);

  useEffect(() => {
    const task = window.setTimeout(() => { void loadDirectory(); }, 0);
    return () => window.clearTimeout(task);
  }, [loadDirectory]);

  const { register, handleSubmit, setValue, reset, formState: { errors, isSubmitting } } = useForm<InviteForm>({
    resolver: zodResolver(inviteSchema), defaultValues: { role: "USER" },
  });

  const onInvite = async (values: InviteForm) => {
    try {
      await inviteTeamMember(request, values.email, values.role);
      toast.success(t("invitationSent", { email: values.email }));
      reset(); setShowInvite(false); await loadDirectory();
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.invite")); }
  };

  const updateMember = (updated: TeamMember) => setDirectory(current => ({
    ...current, members: current.members.map(member => member.id === updated.id ? updated : member),
  }));

  const changeRole = async (member: TeamMember, role: UserRole) => {
    setBusyId(member.id);
    try { updateMember(await updateTeamMemberRole(request, member.id, role)); toast.success(t("roleUpdated")); }
    catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.role")); }
    finally { setBusyId(null); }
  };

  const changeStatus = async (member: TeamMember) => {
    const status: UserStatus = member.status === "ACTIVE" ? "INACTIVE" : "ACTIVE";
    if (status === "INACTIVE" && !window.confirm(t("confirmDeactivate", { name: member.fullName || member.email }))) return;
    setBusyId(member.id);
    try { updateMember(await updateTeamMemberStatus(request, member.id, status)); toast.success(status === "ACTIVE" ? t("reactivated") : t("deactivated")); }
    catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.status")); }
    finally { setBusyId(null); }
  };

  const resend = async (id: string) => {
    setBusyId(id);
    try { await resendTeamInvitation(request, id); toast.success(t("resent")); await loadDirectory(); }
    catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.resend")); }
    finally { setBusyId(null); }
  };

  const cancel = async (id: string, email: string) => {
    if (!window.confirm(t("confirmCancel", { email }))) return;
    setBusyId(id);
    try { await cancelTeamInvitation(request, id); toast.success(t("cancelled")); setDirectory(current => ({ ...current, invitations: current.invitations.filter(item => item.id !== id) })); }
    catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.cancel")); }
    finally { setBusyId(null); }
  };

  return <div className="space-y-6">
    <div className="flex items-center justify-between gap-4">
      <div><h2 className="text-xl font-semibold text-slate-800">{t("title")}</h2><p className="mt-1 text-sm text-slate-500">{t("description")}</p></div>
      {isAdmin && <Button size="sm" onClick={() => setShowInvite(true)} className="gap-1.5 bg-indigo-600 text-white hover:bg-indigo-700"><UserPlus className="size-4" />{t("inviteUser")}</Button>}
    </div>

    {error && <div role="alert" className="flex items-center justify-between rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-800"><span className="flex items-center gap-2"><AlertCircle className="size-4" />{error}</span><Button size="sm" variant="outline" onClick={() => void loadDirectory()}>{t("retry")}</Button></div>}

    <Card>{loading ? <TableSkeleton /> : directory.members.length + directory.invitations.length === 0 ? <CardContent className="py-16 text-center"><Users className="mx-auto mb-3 size-10 text-slate-300" /><p className="font-medium text-slate-500">{t("empty")}</p>{isAdmin && <p className="mt-1 text-sm text-slate-400">{t("emptyDescription")}</p>}</CardContent> : <Table>
      <TableHeader><TableRow><TableHead>{t("table.member")}</TableHead><TableHead>{t("table.role")}</TableHead><TableHead>{t("table.status")}</TableHead><TableHead>{t("table.joinedExpires")}</TableHead>{isAdmin && <TableHead className="text-right">{t("table.actions")}</TableHead>}</TableRow></TableHeader>
      <TableBody>
        {directory.members.map(member => {
          const finalAdmin = member.role === "TENANT_ADMIN" && member.status === "ACTIVE" && activeAdminCount === 1;
          const protectedAction = member.currentUser || finalAdmin;
          return <TableRow key={`member-${member.id}`}>
            <TableCell><div className="flex items-center gap-3"><Avatar className="size-9"><AvatarFallback className="bg-indigo-100 text-xs text-indigo-700">{initials(member.fullName || member.email)}</AvatarFallback></Avatar><div><p className="font-medium text-slate-800">{member.fullName || t("unnamed")}{member.currentUser && <span className="ml-2 text-xs font-normal text-slate-400">{t("you")}</span>}</p><p className="text-sm text-slate-500">{member.email}</p></div></div></TableCell>
            <TableCell>{isAdmin ? <Select value={member.role} disabled={busyId === member.id || protectedAction} onValueChange={value => void changeRole(member, value as UserRole)}><SelectTrigger className="w-36"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="TENANT_ADMIN">{t("roles.admin")}</SelectItem><SelectItem value="USER">{t("roles.user")}</SelectItem></SelectContent></Select> : <span className="text-sm">{roleLabel(member.role)}</span>}</TableCell>
            <TableCell><StatusBadge status={member.status} /></TableCell><TableCell className="text-sm text-slate-500">{formatDate(member.joinedAt)}</TableCell>
            {isAdmin && <TableCell className="text-right"><Button size="sm" variant="outline" disabled={busyId === member.id || (member.status === "ACTIVE" && protectedAction)} onClick={() => void changeStatus(member)}>{member.status === "ACTIVE" ? t("deactivate") : t("reactivate")}</Button></TableCell>}
          </TableRow>;
        })}
        {directory.invitations.map(invitation => <TableRow key={`invite-${invitation.id}`}>
          <TableCell><div className="flex items-center gap-3"><div className="flex size-9 items-center justify-center rounded-full bg-amber-50"><Mail className="size-4 text-amber-600" /></div><div><p className="font-medium text-slate-800">{t("pendingInvitation")}</p><p className="text-sm text-slate-500">{invitation.email}</p></div></div></TableCell>
          <TableCell className="text-sm">{roleLabel(invitation.role)}</TableCell><TableCell><StatusBadge status={invitation.status} /></TableCell><TableCell className="text-sm text-slate-500">{t("expires", { date: formatDate(invitation.expiresAt) })}</TableCell>
          {isAdmin && <TableCell className="space-x-2 text-right"><Button size="sm" variant="outline" disabled={busyId === invitation.id} onClick={() => void resend(invitation.id)}><RefreshCw className="mr-1 size-3.5" />{t("resend")}</Button>{invitation.status === "PENDING" && <Button size="sm" variant="outline" disabled={busyId === invitation.id} onClick={() => void cancel(invitation.id, invitation.email)}>{t("cancel")}</Button>}</TableCell>}
        </TableRow>)}
      </TableBody>
    </Table>}</Card>

    <Dialog open={showInvite} onOpenChange={setShowInvite}><DialogContent><DialogHeader><DialogTitle>{t("inviteMember")}</DialogTitle></DialogHeader><form onSubmit={handleSubmit(onInvite)} className="space-y-4"><div className="space-y-1.5"><Label htmlFor="inviteEmail">{t("emailAddress")}</Label><Input id="inviteEmail" type="email" placeholder="colleague@company.com" {...register("email")} />{errors.email && <p className="text-xs text-red-600">{errors.email.message}</p>}</div><div className="space-y-1.5"><Label>{t("role")}</Label><Select defaultValue="USER" onValueChange={value => setValue("role", value as UserRole)}><SelectTrigger><SelectValue /></SelectTrigger><SelectContent><SelectItem value="TENANT_ADMIN">{t("roles.admin")}</SelectItem><SelectItem value="USER">{t("roles.user")}</SelectItem></SelectContent></Select></div><DialogFooter><Button variant="outline" type="button" onClick={() => setShowInvite(false)}>{t("cancel")}</Button><Button type="submit" disabled={isSubmitting} className="bg-indigo-600 text-white hover:bg-indigo-700">{isSubmitting ? t("sending") : t("sendInvitation")}</Button></DialogFooter></form></DialogContent></Dialog>
  </div>;
}
