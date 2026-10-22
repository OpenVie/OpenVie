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
import { AlertCircle, Eye, EyeOff, KeyRound, Loader2, Plus, UserCheck, UserX, Users, Wand2 } from "lucide-react";
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
import { ListPager, usePagedList } from "@/components/ui/list-pager";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Skeleton } from "@/components/ui/skeleton";
import { Card, CardContent } from "@/components/ui/card";

type CreateUserForm = { email: string; fullName: string; password: string };
type PasswordForm = { password: string; confirmPassword: string };
const initials = (name: string) => name.split(" ").filter(Boolean).map(part => part[0]).join("").toUpperCase().slice(0, 2) || "?";

const PASSWORD_BUCKETS = ["abcdefghjkmnpqrstuvwxyz", "ABCDEFGHJKLMNPQRSTUVWXYZ", "23456789", "!@#$%&*+?"];
/** 16-character temporary password: crypto-random with a guaranteed class mix. */
function generateTempPassword(): string {
  const random = new Uint32Array(24);
  crypto.getRandomValues(random);
  const pool = PASSWORD_BUCKETS.join("");
  const chars = PASSWORD_BUCKETS.map((bucket, index) => bucket[random[index] % bucket.length]);
  for (let i = chars.length; i < 16; i++) chars.push(pool[random[i] % pool.length]);
  for (let i = chars.length - 1; i > 0; i--) {
    const j = random[8 + i] % (i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join("");
}

function StatusBadge({ status }: { status: string }) {
  const t = useTranslations("Users");
  const active = status === "ACTIVE";
  return <span className={`ov-member-status ${active ? "ov-member-status-active" : "ov-member-status-inactive"}`}><i aria-hidden="true" />{active ? t("statuses.active") : t("statuses.inactive")}</span>;
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
  const pagedOrgMembers = usePagedList(orgMembers);
  const [orgLoading, setOrgLoading] = useState(true);
  const [orgError, setOrgError] = useState<string | null>(null);
  const [showCreateUser, setShowCreateUser] = useState(false);
  const [passwordTarget, setPasswordTarget] = useState<OrganizationMember | null>(null);
  const [deactivateTarget, setDeactivateTarget] = useState<OrganizationMember | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  // Generated passwords are shown to the admin so they can share the
  // temporary secret with the new member out of band.
  const [showCreatedPassword, setShowCreatedPassword] = useState(false);

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

  const fillGeneratedPassword = () => {
    createUserForm.setValue("password", generateTempPassword(), { shouldValidate: true, shouldDirty: true });
    setShowCreatedPassword(true);
  };

  const onCreateUser = async (values: CreateUserForm) => {
    try {
      await createOrganizationUserApi(request, values);
      toast.success(t("userCreated"));
      createUserForm.reset();
      setShowCreatedPassword(false);
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
    if (user.status === "ACTIVE") {
      setDeactivateTarget(user);
      return;
    }
    await performStatusChange(user, "ACTIVE");
  };

  const performStatusChange = async (user: OrganizationMember, status: UserStatus) => {
    setBusyId(user.id);
    try {
      const updated = await updateOrgUserStatusApi(request, user.id, status);
      setOrgMembers(current => current.map(item => item.id === updated.id ? updated : item));
      toast.success(status === "ACTIVE" ? t("reactivated") : t("deactivated"));
      setDeactivateTarget(null);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("fallback.status"));
    } finally {
      setBusyId(null);
    }
  };

  return <div className="space-y-6">
    <div className="flex items-center justify-between gap-4">
      <div>
        <h2 className="text-xl font-semibold text-foreground">{t("tabs.organization")}</h2>
        <p className="mt-1 text-sm text-muted-foreground">{t("organizationDescription")}</p>
      </div>
      <Button
        size="sm"
        onClick={() => {
          setShowCreateUser(true);
          createUserForm.reset();
          setShowCreatedPassword(false);
        }}
        className="gap-1.5"
      >
        <Plus className="size-4" />
        {t("createUser")}
      </Button>
    </div>

    {orgError && (
      <div role="alert" className="flex items-center justify-between rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
        <span className="flex items-center gap-2"><AlertCircle className="size-4" />{orgError}</span>
        <Button size="sm" variant="outline" onClick={() => void loadOrgMembers()}>{t("retry")}</Button>
      </div>
    )}

    <Card>
      {orgLoading ? (
        <TableSkeleton />
      ) : orgMembers.length === 0 ? (
        <CardContent className="py-16 text-center">
          <Users className="mx-auto mb-3 size-10 text-muted-foreground opacity-50" />
          <p className="font-medium text-muted-foreground">{t("empty")}</p>
        </CardContent>
      ) : (
        <>
        <div className="divide-y divide-border sm:hidden">
          {pagedOrgMembers.slice.map(user => {
            const isOwnerRow = user.orgRole === "ORG_OWNER";
            return (
              <div key={`org-user-mobile-${user.id}`} className="p-3">
                <div className="flex items-center gap-3">
                  <Avatar className="size-8 shrink-0">
                    <AvatarFallback className="bg-primary/15 text-xs text-primary">
                      {initials(user.fullName || user.email)}
                    </AvatarFallback>
                  </Avatar>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium text-foreground">
                      {user.fullName || t("unnamed")}
                      {user.currentUser && <span className="ml-1.5 text-xs font-normal text-muted-foreground">{t("you")}</span>}
                    </p>
                    <p className="truncate text-xs text-muted-foreground">{user.email}</p>
                  </div>
                  <StatusBadge status={user.status} />
                </div>
                <p className="mt-1 text-xs text-muted-foreground">
                  {isOwnerRow ? t("orgRoles.owner") : t("orgRoles.member")} · {formatDate(user.createdAt)}
                </p>
                <div className="mt-2.5 flex flex-wrap justify-end gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busyId === user.id || user.currentUser}
                    title={t("setPasswordHint")}
                    onClick={() => { setPasswordTarget(user); passwordForm.reset(); }}
                  >
                    <KeyRound className="size-3.5" />
                    {t("setPassword")}
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busyId === user.id || (user.status === "ACTIVE" && (user.currentUser || isOwnerRow))}
                    onClick={() => void changeStatus(user)}
                  >
                    {user.status === "ACTIVE" ? <UserX className="size-3.5" /> : <UserCheck className="size-3.5" />}
                    {user.status === "ACTIVE" ? t("deactivate") : t("reactivate")}
                  </Button>
                </div>
              </div>
            );
          })}
        </div>
        <div className="hidden sm:block">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("table.member")}</TableHead>
              <TableHead>{t("orgRole")}</TableHead>
              <TableHead>{t("table.status")}</TableHead>
              <TableHead className="hidden sm:table-cell">{t("table.joinedExpires")}</TableHead>
              <TableHead className="text-right">{t("table.actions")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {pagedOrgMembers.slice.map(user => {
              const isOwnerRow = user.orgRole === "ORG_OWNER";
              return (
                <TableRow key={`org-user-${user.id}`}>
                  <TableCell>
                    <div className="flex items-center gap-3">
                      <Avatar className="size-9">
                        <AvatarFallback className="bg-primary/15 text-xs text-primary">
                          {initials(user.fullName || user.email)}
                        </AvatarFallback>
                      </Avatar>
                      <div className="min-w-0">
                        <p className="truncate text-sm font-medium text-foreground">
                          {user.fullName || t("unnamed")}
                          {user.currentUser && <span className="ml-2 text-xs font-normal text-muted-foreground">{t("you")}</span>}
                        </p>
                        <p className="max-w-44 truncate text-sm text-muted-foreground sm:max-w-none">{user.email}</p>
                      </div>
                    </div>
                  </TableCell>
                  <TableCell>
                    <span className="text-sm font-medium">
                      {isOwnerRow ? t("orgRoles.owner") : t("orgRoles.member")}
                    </span>
                  </TableCell>
                  <TableCell><StatusBadge status={user.status} /></TableCell>
                  <TableCell className="hidden text-sm text-muted-foreground sm:table-cell">{formatDate(user.createdAt)}</TableCell>
                  <TableCell className="space-x-2 text-right">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busyId === user.id || user.currentUser}
                      title={t("setPasswordHint")}
                      onClick={() => { setPasswordTarget(user); passwordForm.reset(); }}
                    >
                      <KeyRound className="size-3.5" />
                      {t("setPassword")}
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busyId === user.id || (user.status === "ACTIVE" && (user.currentUser || isOwnerRow))}
                      onClick={() => void changeStatus(user)}
                    >
                      {user.status === "ACTIVE" ? <UserX className="size-3.5" /> : <UserCheck className="size-3.5" />}
                      {user.status === "ACTIVE" ? t("deactivate") : t("reactivate")}
                    </Button>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
        </div>
        <ListPager
          page={pagedOrgMembers.page}
          pageCount={pagedOrgMembers.pageCount}
          size={pagedOrgMembers.size}
          total={pagedOrgMembers.total}
          onPageChange={pagedOrgMembers.setPage}
          onSizeChange={(nextSize) => { pagedOrgMembers.setSize(nextSize); pagedOrgMembers.setPage(1); }}
        />
        </>
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
              <p className="text-xs text-destructive">{createUserForm.formState.errors.email.message}</p>
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
              <p className="text-xs text-destructive">{createUserForm.formState.errors.fullName.message}</p>
            )}
          </div>
          <div className="space-y-1.5">
            <div className="flex items-center justify-between gap-2">
              <Label htmlFor="createPassword">{t("initialPassword")}</Label>
              <Button
                type="button"
                size="sm"
                variant="outline"
                className="h-7 gap-1 px-2 text-xs"
                onClick={fillGeneratedPassword}
              >
                <Wand2 className="size-3.5" />
                {t("generatePassword")}
              </Button>
            </div>
            <div className="relative">
              <Input
                id="createPassword"
                type={showCreatedPassword ? "text" : "password"}
                autoComplete="new-password"
                placeholder="••••••••••••"
                className="pr-10"
                {...createUserForm.register("password")}
              />
              <Button
                type="button"
                size="icon"
                variant="ghost"
                className="absolute right-1 top-1/2 size-7 -translate-y-1/2 text-muted-foreground"
                title={showCreatedPassword ? t("hidePassword") : t("showPassword")}
                onClick={() => setShowCreatedPassword(value => !value)}
              >
                {showCreatedPassword ? <EyeOff className="size-3.5" /> : <Eye className="size-3.5" />}
              </Button>
            </div>
            {createUserForm.formState.errors.password && (
              <p className="text-xs text-destructive">{createUserForm.formState.errors.password.message}</p>
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
        <p className="text-sm text-muted-foreground">{t("setPasswordNote")}</p>
        <form onSubmit={passwordForm.handleSubmit(onSetPassword)} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="memberPassword">{t("newPassword")}</Label>
            <Input id="memberPassword" type="password" autoComplete="new-password" {...passwordForm.register("password")} />
            {passwordForm.formState.errors.password && <p className="text-xs text-destructive">{passwordForm.formState.errors.password.message}</p>}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="confirmPassword">{t("confirmPassword")}</Label>
            <Input id="confirmPassword" type="password" autoComplete="new-password" {...passwordForm.register("confirmPassword")} />
            {passwordForm.formState.errors.confirmPassword && <p className="text-xs text-destructive">{passwordForm.formState.errors.confirmPassword.message}</p>}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setPasswordTarget(null)}>{t("cancel")}</Button>
            <Button type="submit" disabled={passwordForm.formState.isSubmitting}>{t("setPassword")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    {/* Deactivate User Confirmation Dialog */}
    <Dialog open={deactivateTarget !== null} onOpenChange={(open) => { if (!open && !busyId) setDeactivateTarget(null); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("deactivate")}</DialogTitle>
          <DialogDescription>
            {deactivateTarget && t("confirmDeactivate", { name: deactivateTarget.fullName || deactivateTarget.email })}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={Boolean(busyId)}
            onClick={() => setDeactivateTarget(null)}
          >
            {t("cancel")}
          </Button>
          <Button
            type="button"
            variant="destructive"
            disabled={Boolean(busyId)}
            onClick={() => {
              if (deactivateTarget) void performStatusChange(deactivateTarget, "INACTIVE");
            }}
          >
            {busyId === deactivateTarget?.id ? <Loader2 className="size-4 animate-spin" /> : t("deactivate")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>;
}
