"use client";

import { useCallback, useEffect, useState } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import toast from "react-hot-toast";
import { AlertCircle, KeyRound, Plus, Users } from "lucide-react";
import { useApiClient } from "@/hooks/useApiClient";
import {
  createOrganizationUserApi,
  getOrganizationMembersApi,
  setOrgUserPasswordApi,
  updateOrgUserStatusApi,
} from "@/lib/users-api";
import type { OrganizationMember, UserStatus } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Skeleton } from "@/components/ui/skeleton";
import { Card, CardContent } from "@/components/ui/card";

type CreateUserForm = { email: string; fullName: string; password: string };
type PasswordForm = { password: string; confirmPassword: string };
const initials = (name: string) => name.split(" ").filter(Boolean).map(part => part[0]).join("").toUpperCase().slice(0, 2) || "?";

function StatusBadge({ status }: { status: string }) {
  const t = useTranslations("Users");
  const active = status === "ACTIVE";
  return <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${
    active ? "bg-green-100 text-green-800" : "bg-slate-100 text-slate-600"
  }`}>{active ? t("statuses.active") : t("statuses.inactive")}</span>;
}

function TableSkeleton() {
  return <div className="space-y-4 p-5">{Array.from({ length: 4 }).map((_, index) => (
    <div key={index} className="flex items-center gap-4"><Skeleton className="size-8 rounded-full" /><Skeleton className="h-5 flex-1" /><Skeleton className="h-5 w-28" /><Skeleton className="h-5 w-24" /></div>
  ))}</div>;
}

/**
 * Admin panel: every account in the organization.
 *
 * Set password and activate/deactivate act on the whole org account (not on a
 * workspace membership), which is why they live here behind ORG_OWNER instead
 * of on the workspace Users page. Every call goes through `/api/v1/admin/*`.
 */
export default function OrganizationPage() {
  const t = useTranslations("Users");
  const format = useFormatter();
  const { request } = useApiClient();
  const [orgMembers, setOrgMembers] = useState<OrganizationMember[]>([]);
  const [orgLoading, setOrgLoading] = useState(true);
  const [orgError, setOrgError] = useState<string | null>(null);
  const [showCreateUser, setShowCreateUser] = useState(false);
  const [passwordTarget, setPasswordTarget] = useState<OrganizationMember | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const formatDate = (value: string) => format.dateTime(new Date(value), { dateStyle: "medium" });

  const loadOrgMembers = useCallback(async () => {
    setOrgLoading(true);
    setOrgError(null);
    try {
      setOrgMembers(await getOrganizationMembersApi(request));
    } catch (cause) {
      setOrgError(cause instanceof Error ? cause.message : t("fallback.load"));
    } finally {
      setOrgLoading(false);
    }
  }, [request, t]);

  useEffect(() => {
    const task = window.setTimeout(() => { void loadOrgMembers(); }, 0);
    return () => window.clearTimeout(task);
  }, [loadOrgMembers]);

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

  const onCreateUser = async (values: CreateUserForm) => {
    try {
      await createOrganizationUserApi(request, values);
      toast.success(t("userCreated"));
      createUserForm.reset();
      setShowCreateUser(false);
      await loadOrgMembers();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("fallback.createUser"));
    }
  };

  const onSetPassword = async (values: PasswordForm) => {
    if (!passwordTarget) return;
    setBusyId(passwordTarget.id);
    try {
      await setOrgUserPasswordApi(request, passwordTarget.id, values.password);
      toast.success(t("passwordSet", { name: passwordTarget.fullName || passwordTarget.email }));
      setPasswordTarget(null);
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.password")); }
    finally { setBusyId(null); }
  };

  const changeStatus = async (user: OrganizationMember) => {
    const status: UserStatus = user.status === "ACTIVE" ? "INACTIVE" : "ACTIVE";
    if (status === "INACTIVE" && !window.confirm(t("confirmDeactivate", { name: user.fullName || user.email }))) return;
    setBusyId(user.id);
    try {
      const updated = await updateOrgUserStatusApi(request, user.id, status);
      setOrgMembers(current => current.map(item => item.id === updated.id ? updated : item));
      toast.success(status === "ACTIVE" ? t("reactivated") : t("deactivated"));
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.status")); }
    finally { setBusyId(null); }
  };

  return <div className="space-y-6">
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
              <TableHead className="text-right">{t("table.actions")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {orgMembers.map(user => {
              const isOwnerRow = user.orgRole === "ORG_OWNER";
              return (
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
                      {isOwnerRow ? t("orgRoles.owner") : t("orgRoles.member")}
                    </span>
                  </TableCell>
                  <TableCell><StatusBadge status={user.status} /></TableCell>
                  <TableCell className="text-sm text-slate-500">{formatDate(user.createdAt)}</TableCell>
                  <TableCell className="space-x-2 text-right">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busyId === user.id || user.currentUser}
                      title={t("setPasswordHint")}
                      onClick={() => { setPasswordTarget(user); passwordForm.reset(); }}
                    >
                      <KeyRound className="mr-1 size-3.5" />{t("setPassword")}
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busyId === user.id || (user.status === "ACTIVE" && (user.currentUser || isOwnerRow))}
                      onClick={() => void changeStatus(user)}
                    >
                      {user.status === "ACTIVE" ? t("deactivate") : t("reactivate")}
                    </Button>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}
    </Card>

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
