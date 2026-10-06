"use client";


import { useCallback, useEffect, useMemo, useState } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import toast from "react-hot-toast";
import { AlertCircle, KeyRound, Plus, UserPlus, Users } from "lucide-react";
// Invitation UI is intentionally disabled; retain these imports for its re-enable path.
// import { Mail, RefreshCw } from "lucide-react";
import { useApiClient } from "@/hooks/useApiClient";
import { useAuthStore } from "@/components/providers/StoreProvider";
import {
  addMemberToWorkspaceApi,
  createOrganizationUserApi,
  getOrganizationMembersApi,
  getTeamDirectory,
  setTeamMemberPassword,
  updateTeamMemberRole,
  updateTeamMemberStatus,
} from "@/lib/users-api";
// Invitation UI is intentionally disabled; restore these with its commented handlers and JSX below.
// import { cancelTeamInvitation, inviteTeamMember, resendTeamInvitation } from "@/lib/users-api";
import type { OrganizationMember, TeamDirectory, TeamMember, UserStatus, WorkspaceRole } from "@/types";
import { cn } from "@/lib/utils";
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
type CreateUserForm = { email: string; fullName: string; password: string };
type PasswordForm = { password: string; confirmPassword: string };
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
  const [directory, setDirectory] = useState<TeamDirectory>({
    members: [],
    invitations: [],
    visibility: "PRIVATE",
  });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Invitation UI disabled: const [showInvite, setShowInvite] = useState(false);
  const [showAddMember, setShowAddMember] = useState(false);
  const [showCreateUser, setShowCreateUser] = useState(false);
  const [passwordTarget, setPasswordTarget] = useState<TeamMember | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<"workspace" | "organization">("workspace");
  const [orgMembers, setOrgMembers] = useState<OrganizationMember[]>([]);
  const [orgLoading, setOrgLoading] = useState(false);
  const [orgError, setOrgError] = useState<string | null>(null);

  const isOwner = authUser?.orgRole === "ORG_OWNER";
  const isAdmin = authUser?.workspaceRole === "WORKSPACE_ADMIN" || isOwner;
  const isPublicWorkspace = directory.visibility === "PUBLIC";
  const canAddMember = isPublicWorkspace || isAdmin;
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

  const loadOrgMembers = useCallback(async () => {
    if (!isOwner) return;
    setOrgLoading(true);
    setOrgError(null);
    try {
      setOrgMembers(await getOrganizationMembersApi(request));
    } catch (cause) {
      setOrgError(cause instanceof Error ? cause.message : t("fallback.load"));
    } finally {
      setOrgLoading(false);
    }
  }, [isOwner, request, t]);

  useEffect(() => {
    if (activeTab === "organization" && isOwner) {
      void loadOrgMembers();
    }
  }, [activeTab, isOwner, loadOrgMembers]);

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

  const createUserSchema = z.object({
    email: z.string().email(t("validEmail")),
    fullName: z.string().trim().min(1, t("fullName")),
    password: z.string().min(12, t("passwordTooShort")),
  });
  const createUserForm = useForm<CreateUserForm>({
    resolver: zodResolver(createUserSchema),
  });
  const passwordSchema = z.object({
    password: z.string().min(12, t("passwordTooShort")),
    confirmPassword: z.string(),
  }).refine(values => values.password === values.confirmPassword, {
    path: ["confirmPassword"], message: t("passwordsMismatch"),
  });
  const passwordForm = useForm<PasswordForm>({ resolver: zodResolver(passwordSchema) });

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
      await addMemberToWorkspaceApi(request, values.email, values.role);
      toast.success(t("memberAdded"));
      addMemberForm.reset();
      setShowAddMember(false);
      await loadDirectory();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("fallback.addMember"));
    }
  };

  const onCreateUser = async (values: CreateUserForm) => {
    try {
      await createOrganizationUserApi(request, values);
      toast.success(t("userCreated"));
      createUserForm.reset();
      setShowCreateUser(false);
      await loadOrgMembers();
      await loadDirectory();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("fallback.createUser"));
    }
  };
  const onSetPassword = async (values: PasswordForm) => {
    if (!passwordTarget) return;
    setBusyId(passwordTarget.id);
    try {
      await setTeamMemberPassword(request, passwordTarget.id, values.password);
      toast.success(t("passwordSet", { name: passwordTarget.fullName || passwordTarget.email }));
      setPasswordTarget(null);
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.password")); }
    finally { setBusyId(null); }
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

  const changeStatus = async (member: TeamMember) => {
    const status: UserStatus = member.status === "ACTIVE" ? "INACTIVE" : "ACTIVE";
    if (status === "INACTIVE" && !window.confirm(t("confirmDeactivate", { name: member.fullName || member.email }))) return;
    setBusyId(member.id);
    try { updateMember(await updateTeamMemberStatus(request, member.id, status)); toast.success(status === "ACTIVE" ? t("reactivated") : t("deactivated")); }
    catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.status")); }
    finally { setBusyId(null); }
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
    {isOwner && (
      <div className="flex border-b border-slate-200">
        <button
          type="button"
          onClick={() => setActiveTab("workspace")}
          className={cn(
            "border-b-2 px-4 py-2.5 text-sm font-medium transition-colors",
            activeTab === "workspace"
              ? "border-indigo-600 text-indigo-600"
              : "border-transparent text-slate-500 hover:text-slate-700"
          )}
        >
          {t("tabs.workspace")}
        </button>
        <button
          type="button"
          onClick={() => setActiveTab("organization")}
          className={cn(
            "border-b-2 px-4 py-2.5 text-sm font-medium transition-colors",
            activeTab === "organization"
              ? "border-indigo-600 text-indigo-600"
              : "border-transparent text-slate-500 hover:text-slate-700"
          )}
        >
          {t("tabs.organization")}
        </button>
      </div>
    )}

    {activeTab === "workspace" ? (
      <>
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
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busyId === member.id || member.currentUser}
                            title={t("setPasswordHint")}
                            onClick={() => { setPasswordTarget(member); passwordForm.reset(); }}
                          >
                            <KeyRound className="mr-1 size-3.5" />{t("setPassword")}
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busyId === member.id || (member.status === "ACTIVE" && protectedAction)}
                            onClick={() => void changeStatus(member)}
                          >
                            {member.status === "ACTIVE" ? t("deactivate") : t("reactivate")}
                          </Button>
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
      </>
    ) : (
      <>
        <div className="flex items-center justify-between gap-4">
          <div>
            <h2 className="text-xl font-semibold text-slate-800">{t("tabs.organization")}</h2>
            <p className="mt-1 text-sm text-slate-500">{t("organizationDescription")}</p>
          </div>
          <Button
            size="sm"
            onClick={() => {
              setShowCreateUser(true);
              createUserForm.reset();
            }}
            className="gap-1.5 bg-indigo-600 text-white hover:bg-indigo-700"
          >
            <Plus className="size-4" />
            {t("createUser")}
          </Button>
        </div>

        {orgError && (
          <div role="alert" className="flex items-center justify-between rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            <span className="flex items-center gap-2"><AlertCircle className="size-4" />{orgError}</span>
            <Button size="sm" variant="outline" onClick={() => void loadOrgMembers()}>{t("retry")}</Button>
          </div>
        )}

        <Card>
          {orgLoading ? (
            <TableSkeleton />
          ) : orgMembers.length === 0 ? (
            <CardContent className="py-16 text-center">
              <Users className="mx-auto mb-3 size-10 text-slate-300" />
              <p className="font-medium text-slate-500">{t("empty")}</p>
            </CardContent>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("table.member")}</TableHead>
                  <TableHead>{t("orgRole")}</TableHead>
                  <TableHead>{t("table.status")}</TableHead>
                  <TableHead>{t("table.joinedExpires")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {orgMembers.map(user => (
                  <TableRow key={`org-user-${user.id}`}>
                    <TableCell>
                      <div className="flex items-center gap-3">
                        <Avatar className="size-9">
                          <AvatarFallback className="bg-indigo-100 text-xs text-indigo-700">
                            {initials(user.fullName || user.email)}
                          </AvatarFallback>
                        </Avatar>
                        <div>
                          <p className="font-medium text-slate-800">
                            {user.fullName || t("unnamed")}
                            {user.currentUser && <span className="ml-2 text-xs font-normal text-slate-400">{t("you")}</span>}
                          </p>
                          <p className="text-sm text-slate-500">{user.email}</p>
                        </div>
                      </div>
                    </TableCell>
                    <TableCell>
                      <span className="text-sm font-medium">
                        {user.orgRole === "ORG_OWNER" ? t("orgRoles.owner") : t("orgRoles.member")}
                      </span>
                    </TableCell>
                    <TableCell><StatusBadge status={user.status} /></TableCell>
                    <TableCell className="text-sm text-slate-500">{formatDate(user.createdAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </Card>
      </>
    )}

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
              value={addMemberForm.watch("role")}
              disabled={!isAdmin}
              onValueChange={value => addMemberForm.setValue("role", value as WorkspaceRole)}
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

    {/* Create Organization User Dialog */}
    <Dialog open={showCreateUser} onOpenChange={setShowCreateUser}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("createUserTitle")}</DialogTitle>
          <DialogDescription>{t("initialPasswordHint")}</DialogDescription>
        </DialogHeader>
        <form onSubmit={createUserForm.handleSubmit(onCreateUser)} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="createEmail">{t("emailAddress")}</Label>
            <Input
              id="createEmail"
              type="email"
              placeholder="colleague@company.com"
              {...createUserForm.register("email")}
            />
            {createUserForm.formState.errors.email && (
              <p className="text-xs text-red-600">{createUserForm.formState.errors.email.message}</p>
            )}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="createFullName">{t("fullName")}</Label>
            <Input
              id="createFullName"
              placeholder="Ada Lovelace"
              {...createUserForm.register("fullName")}
            />
            {createUserForm.formState.errors.fullName && (
              <p className="text-xs text-red-600">{createUserForm.formState.errors.fullName.message}</p>
            )}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="createPassword">{t("initialPassword")}</Label>
            <Input
              id="createPassword"
              type="password"
              autoComplete="new-password"
              placeholder="••••••••••••"
              {...createUserForm.register("password")}
            />
            {createUserForm.formState.errors.password && (
              <p className="text-xs text-red-600">{createUserForm.formState.errors.password.message}</p>
            )}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setShowCreateUser(false)}>{t("cancel")}</Button>
            <Button type="submit" disabled={createUserForm.formState.isSubmitting}>{t("createUser")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <Dialog open={passwordTarget !== null} onOpenChange={open => { if (!open) setPasswordTarget(null); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>{t("setPasswordTitle", { name: passwordTarget?.fullName || passwordTarget?.email || "" })}</DialogTitle></DialogHeader>
        <p className="text-sm text-slate-500">{t("setPasswordNote")}</p>
        <form onSubmit={passwordForm.handleSubmit(onSetPassword)} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="memberPassword">{t("newPassword")}</Label>
            <Input id="memberPassword" type="password" autoComplete="new-password" {...passwordForm.register("password")} />
            {passwordForm.formState.errors.password && <p className="text-xs text-red-600">{passwordForm.formState.errors.password.message}</p>}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="confirmPassword">{t("confirmPassword")}</Label>
            <Input id="confirmPassword" type="password" autoComplete="new-password" {...passwordForm.register("confirmPassword")} />
            {passwordForm.formState.errors.confirmPassword && <p className="text-xs text-red-600">{passwordForm.formState.errors.confirmPassword.message}</p>}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setPasswordTarget(null)}>{t("cancel")}</Button>
            <Button type="submit" disabled={passwordForm.formState.isSubmitting}>{t("setPassword")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  </div>;
}
