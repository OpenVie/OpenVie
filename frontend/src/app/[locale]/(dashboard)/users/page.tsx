"use client";


import { useCallback, useEffect, useMemo, useState } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import toast from "react-hot-toast";
import { AlertCircle, Loader2, UserPlus, Users } from "lucide-react";
// Invitation UI is intentionally disabled; retain these imports for its re-enable path.
// import { Mail, RefreshCw } from "lucide-react";
import { useApiClient } from "@/hooks/useApiClient";
import { useAuthStore } from "@/components/providers/StoreProvider";
import {
  addMemberToWorkspaceApi,
  getTeamDirectory,
  removeWorkspaceMemberApi,
  updateTeamMemberRole,
} from "@/lib/users-api";
// Invitation UI is intentionally disabled; restore these with its commented handlers and JSX below.
// import { cancelTeamInvitation, inviteTeamMember, resendTeamInvitation } from "@/lib/users-api";
import type { TeamDirectory, TeamMember, WorkspaceRole } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Skeleton } from "@/components/ui/skeleton";
import { Card, CardContent } from "@/components/ui/card";

/*
 * Invitation UI is intentionally disabled rather than deleted. Re-enable this
 * type together with the commented form state and handlers below.
 * type InviteForm = { email: string; role: WorkspaceRole };
 */
type AddMemberForm = { email: string; role: WorkspaceRole };
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

/**
 * Workspace membership for the caller's active workspace.
 *
 * Organization account administration (create user, set password,
 * activate/deactivate) lives on the admin panel's Organization page instead:
 * those actions affect the whole org account, not this membership.
 */
export default function UsersPage() {
  const t = useTranslations("Users");
  const format = useFormatter();
  const { request } = useApiClient();
  const authUser = useAuthStore(state => state.user);
  const [directory, setDirectory] = useState<TeamDirectory>({
    members: [],
    invitations: [],
    visibility: "PRIVATE",
    defaultWorkspace: false,
  });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Invitation UI disabled: const [showInvite, setShowInvite] = useState(false);
  const [showAddMember, setShowAddMember] = useState(false);
  // Role select state lives outside react-hook-form: the compiler skips
  // memoizing components that call form.watch() in render.
  const [addMemberRole, setAddMemberRole] = useState<WorkspaceRole>("MEMBER");
  const [removeTarget, setRemoveTarget] = useState<TeamMember | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const isOwner = authUser?.orgRole === "ORG_OWNER";
  const isAdmin = authUser?.workspaceRole === "WORKSPACE_ADMIN" || isOwner;
  const isPublicWorkspace = directory.visibility === "PUBLIC";
  const canAddMember = isPublicWorkspace || isAdmin;
  // The default workspace (General) holds every account: removal is refused
  // server-side, so the button is hidden here as well.
  const canRemoveMember = isAdmin && !directory.defaultWorkspace;
  const activeAdminCount = useMemo(() => directory.members.filter(member => member.workspaceRole === "WORKSPACE_ADMIN" && member.status === "ACTIVE").length, [directory.members]);
  // Invitation UI disabled:
  // const inviteSchema = z.object({ email: z.string().email(t("validEmail")), role: z.enum(["WORKSPACE_ADMIN", "MEMBER"]) });
  const roleLabel = (role: WorkspaceRole) => role === "WORKSPACE_ADMIN" ? t("roles.admin") : t("roles.user");
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

  /*
   * Invitation UI disabled:
   * const { register, handleSubmit, setValue, reset, formState: { errors, isSubmitting } } = useForm<InviteForm>({
   *   resolver: zodResolver(inviteSchema), defaultValues: { role: "MEMBER" },
   * });
   */

  const addMemberSchema = z.object({
    email: z.string().email(t("validEmail")),
    role: z.enum(["WORKSPACE_ADMIN", "MEMBER"]),
  });
  const addMemberForm = useForm<AddMemberForm>({
    resolver: zodResolver(addMemberSchema),
    defaultValues: { role: "MEMBER" },
  });

  /*
   * Invitation UI disabled:
   * const onInvite = async (values: InviteForm) => {
   *   try {
   *     await inviteTeamMember(request, values.email, values.role);
   *     toast.success(t("invitationSent", { email: values.email }));
   *     reset(); setShowInvite(false); await loadDirectory();
   *   } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.invite")); }
   * };
   */

  const onAddMember = async (values: AddMemberForm) => {
    try {
      await addMemberToWorkspaceApi(request, values.email, addMemberRole);
      toast.success(t("memberAdded"));
      addMemberForm.reset();
      setAddMemberRole("MEMBER");
      setShowAddMember(false);
      await loadDirectory();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("fallback.addMember"));
    }
  };

  const updateMember = (updated: TeamMember) => setDirectory(current => ({
    ...current, members: current.members.map(member => member.id === updated.id ? updated : member),
  }));

  const changeRole = async (member: TeamMember, role: WorkspaceRole) => {
    setBusyId(member.id);
    try { updateMember(await updateTeamMemberRole(request, member.id, role)); toast.success(t("roleUpdated")); }
    catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.role")); }
    finally { setBusyId(null); }
  };

  const confirmRemoveMember = async () => {
    if (!removeTarget) return;
    setBusyId(removeTarget.id);
    try {
      await removeWorkspaceMemberApi(request, removeTarget.id);
      toast.success(t("memberRemoved"));
      setRemoveTarget(null);
      await loadDirectory();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("fallback.remove"));
    } finally {
      setBusyId(null);
    }
  };

  /*
   * Invitation UI disabled:
   * const resend = async (id: string) => {
   *   setBusyId(id);
   *   try { await resendTeamInvitation(request, id); toast.success(t("resent")); await loadDirectory(); }
   *   catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.resend")); }
   *   finally { setBusyId(null); }
   * };
   *
   * const cancel = async (id: string, email: string) => {
   *   if (!window.confirm(t("confirmCancel", { email }))) return;
   *   setBusyId(id);
   *   try { await cancelTeamInvitation(request, id); toast.success(t("cancelled")); setDirectory(current => ({ ...current, invitations: current.invitations.filter(item => item.id !== id) })); }
   *   catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.cancel")); }
   *   finally { setBusyId(null); }
   * };
   */

  return <div className="space-y-6">
    <div className="flex items-center justify-between gap-4">
      <div>
        <h2 className="text-xl font-semibold text-slate-800">{t("title")}</h2>
        <p className="mt-1 text-sm text-slate-500">{t("description")}</p>
      </div>
      <div className="flex items-center gap-2">
        {/* Invite user button commented out so it can be re-enabled later:
        {isAdmin && <Button size="sm" onClick={() => setShowInvite(true)} className="gap-1.5 bg-indigo-600 text-white hover:bg-indigo-700"><UserPlus className="size-4" />{t("inviteUser")}</Button>}
        */}
        {canAddMember && (
          <Button
            size="sm"
            onClick={() => {
              setShowAddMember(true);
              addMemberForm.reset({ role: "MEMBER" });
              setAddMemberRole("MEMBER");
            }}
            className="gap-1.5 bg-indigo-600 text-white hover:bg-indigo-700"
          >
            <UserPlus className="size-4" />
            {t("addToWorkspace")}
          </Button>
        )}
      </div>
    </div>

    {error && (
      <div role="alert" className="flex items-center justify-between rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        <span className="flex items-center gap-2"><AlertCircle className="size-4" />{error}</span>
        <Button size="sm" variant="outline" onClick={() => void loadDirectory()}>{t("retry")}</Button>
      </div>
    )}

    <Card>
      {loading ? (
        <TableSkeleton />
      ) : directory.members.length === 0 ? (
        <CardContent className="py-16 text-center">
          <Users className="mx-auto mb-3 size-10 text-slate-300" />
          <p className="font-medium text-slate-500">{t("empty")}</p>
          {canAddMember && <p className="mt-1 text-sm text-slate-400">{t("emptyDescription")}</p>}
        </CardContent>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("table.member")}</TableHead>
              <TableHead>{t("table.role")}</TableHead>
              <TableHead>{t("table.status")}</TableHead>
              <TableHead>{t("table.joinedExpires")}</TableHead>
              {isAdmin && <TableHead className="text-right">{t("table.actions")}</TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {directory.members.map(member => {
              const finalAdmin = member.workspaceRole === "WORKSPACE_ADMIN" && member.status === "ACTIVE" && activeAdminCount === 1;
              const protectedAction = member.currentUser || finalAdmin;
              return (
                <TableRow key={`member-${member.id}`}>
                  <TableCell>
                    <div className="flex items-center gap-3">
                      <Avatar className="size-9">
                        <AvatarFallback className="bg-indigo-100 text-xs text-indigo-700">
                          {initials(member.fullName || member.email)}
                        </AvatarFallback>
                      </Avatar>
                      <div>
                        <p className="font-medium text-slate-800">
                          {member.fullName || t("unnamed")}
                          {member.currentUser && <span className="ml-2 text-xs font-normal text-slate-400">{t("you")}</span>}
                        </p>
                        <p className="text-sm text-slate-500">{member.email}</p>
                      </div>
                    </div>
                  </TableCell>
                  <TableCell>
                    {isAdmin ? (
                      <Select
                        value={member.workspaceRole}
                        disabled={busyId === member.id || protectedAction}
                        onValueChange={value => void changeRole(member, value as WorkspaceRole)}
                      >
                        <SelectTrigger className="w-36"><SelectValue /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value="WORKSPACE_ADMIN">{t("roles.admin")}</SelectItem>
                          <SelectItem value="MEMBER">{t("roles.user")}</SelectItem>
                        </SelectContent>
                      </Select>
                    ) : (
                      <span className="text-sm">{roleLabel(member.workspaceRole)}</span>
                    )}
                  </TableCell>
                  <TableCell><StatusBadge status={member.status} /></TableCell>
                  <TableCell className="text-sm text-slate-500">{formatDate(member.joinedAt)}</TableCell>
                  {isAdmin && (
                    <TableCell className="space-x-2 text-right">
                      {canRemoveMember && (
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busyId === member.id || protectedAction}
                          title={t("removeFromWorkspaceHint")}
                          onClick={() => setRemoveTarget(member)}
                        >
                          {t("removeFromWorkspace")}
                        </Button>
                      )}
                    </TableCell>
                  )}
                </TableRow>
              );
            })}

            {/* Pending invitations commented out so they can be re-enabled later:
            {directory.invitations.map(invitation => (
              <TableRow key={`invite-${invitation.id}`}>
                <TableCell>
                  <div className="flex items-center gap-3">
                    <div className="flex size-9 items-center justify-center rounded-full bg-amber-50">
                      <Mail className="size-4 text-amber-600" />
                    </div>
                    <div>
                      <p className="font-medium text-slate-800">{t("pendingInvitation")}</p>
                      <p className="text-sm text-slate-500">{invitation.email}</p>
                    </div>
                  </div>
                </TableCell>
                <TableCell className="text-sm">{roleLabel(invitation.role)}</TableCell>
                <TableCell><StatusBadge status={invitation.status} /></TableCell>
                <TableCell className="text-sm text-slate-500">{t("expires", { date: formatDate(invitation.expiresAt) })}</TableCell>
                {isAdmin && (
                  <TableCell className="space-x-2 text-right">
                    <Button size="sm" variant="outline" disabled={busyId === invitation.id} onClick={() => void resend(invitation.id)}>
                      <RefreshCw className="mr-1 size-3.5" />{t("resend")}
                    </Button>
                    {invitation.status === "PENDING" && (
                      <Button size="sm" variant="outline" disabled={busyId === invitation.id} onClick={() => void cancel(invitation.id, invitation.email)}>
                        {t("cancel")}
                      </Button>
                    )}
                  </TableCell>
                )}
              </TableRow>
            ))}
            */}
          </TableBody>
        </Table>
      )}
    </Card>

    {/* Invite Member dialog commented out so it can be re-enabled later:
    <Dialog open={showInvite} onOpenChange={setShowInvite}>
      <DialogContent>
        <DialogHeader><DialogTitle>{t("inviteMember")}</DialogTitle></DialogHeader>
        <form onSubmit={handleSubmit(onInvite)} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="inviteEmail">{t("emailAddress")}</Label>
            <Input id="inviteEmail" type="email" placeholder="colleague@company.com" {...register("email")} />
            {errors.email && <p className="text-xs text-red-600">{errors.email.message}</p>}
          </div>
          <div className="space-y-1.5">
            <Label>{t("role")}</Label>
            <Select defaultValue="MEMBER" onValueChange={value => setValue("role", value as WorkspaceRole)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="WORKSPACE_ADMIN">{t("roles.admin")}</SelectItem>
                <SelectItem value="MEMBER">{t("roles.user")}</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setShowInvite(false)}>{t("cancel")}</Button>
            <Button type="submit" disabled={isSubmitting}>{t("sendInvitation")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
    */}

    {/* Add Member to Workspace Dialog */}
    <Dialog open={showAddMember} onOpenChange={setShowAddMember}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("addToWorkspaceTitle")}</DialogTitle>
          <DialogDescription>{t("existingMemberHint")}</DialogDescription>
        </DialogHeader>
        <form onSubmit={addMemberForm.handleSubmit(onAddMember)} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="addMemberEmail">{t("emailAddress")}</Label>
            <Input
              id="addMemberEmail"
              type="email"
              placeholder="colleague@company.com"
              {...addMemberForm.register("email")}
            />
            {addMemberForm.formState.errors.email && (
              <p className="text-xs text-red-600">{addMemberForm.formState.errors.email.message}</p>
            )}
          </div>
          <div className="space-y-1.5">
            <Label>{t("role")}</Label>
            <Select
              value={addMemberRole}
              disabled={!isAdmin}
              onValueChange={value => setAddMemberRole(value as WorkspaceRole)}
            >
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                {isAdmin && <SelectItem value="WORKSPACE_ADMIN">{t("roles.admin")}</SelectItem>}
                <SelectItem value="MEMBER">{t("roles.user")}</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setShowAddMember(false)}>{t("cancel")}</Button>
            <Button type="submit" disabled={addMemberForm.formState.isSubmitting}>{t("addMember")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    {/* Remove Member Confirmation Dialog */}
    <Dialog open={removeTarget !== null} onOpenChange={(open) => { if (!open && !busyId) setRemoveTarget(null); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("removeFromWorkspace")}</DialogTitle>
          <DialogDescription>
            {removeTarget && t("confirmRemove", { name: removeTarget.fullName || removeTarget.email })}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={Boolean(busyId)}
            onClick={() => setRemoveTarget(null)}
          >
            {t("cancel")}
          </Button>
          <Button
            type="button"
            variant="destructive"
            disabled={Boolean(busyId)}
            onClick={() => void confirmRemoveMember()}
          >
            {busyId === removeTarget?.id ? <Loader2 className="size-4 animate-spin" /> : t("removeFromWorkspace")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>;
}
