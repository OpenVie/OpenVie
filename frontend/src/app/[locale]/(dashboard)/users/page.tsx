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

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useFormatter, useTranslations } from "next-intl";
import toast from "react-hot-toast";
import { AlertCircle, Loader2, UserMinus, UserPlus, Users, X } from "lucide-react";
import { useApiClient } from "@/hooks/useApiClient";
import { useAuthStore } from "@/components/providers/StoreProvider";
import {
  addMemberToWorkspaceApi,
  getTeamDirectory,
  removeWorkspaceMemberApi,
  suggestOrganizationUsersApi,
  updateTeamMemberRole,
} from "@/lib/users-api";
import type { OrganizationMember, TeamDirectory, TeamMember, WorkspaceRole } from "@/types";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ListPager, usePagedList } from "@/components/ui/list-pager";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Skeleton } from "@/components/ui/skeleton";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

type SelectedMember = { email: string; fullName?: string; id?: string };
const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
function extractValidEmails(text: string): string[] {
  return text
    .split(/[\s,;]+/)
    .map(p => p.trim())
    .filter(p => EMAIL_REGEX.test(p));
}
const initials = (name: string) => name.split(" ").filter(Boolean).map(part => part[0]).join("").toUpperCase().slice(0, 2) || "?";

function StatusBadge({ status }: { status: string }) {
  const t = useTranslations("Users");
  const active = status === "ACTIVE";
  const pending = status === "PENDING";
  return <span className={`ov-member-status ${
    active ? "ov-member-status-active" : pending ? "ov-member-status-pending" : "ov-member-status-inactive"
  }`}><i aria-hidden="true" />{status === "ACTIVE" ? t("statuses.active") : status === "PENDING" ? t("statuses.pending") : t("statuses.inactive")}</span>;
}

function TableSkeleton() {
  return <div className="space-y-4 p-5">{Array.from({ length: 4 }).map((_, index) => (
    <div key={index} className="flex items-center gap-4"><Skeleton className="size-8 rounded-full" /><Skeleton className="h-5 flex-1" /><Skeleton className="h-5 w-28" /><Skeleton className="h-5 w-24" /></div>
  ))}</div>;
}

/** Debounced add-member suggestions: active org accounts not yet in this workspace. */
function MemberSuggestions({
  query,
  onPick,
  excludedEmails = [],
  t,
}: {
  query: string;
  onPick: (member: OrganizationMember) => void;
  excludedEmails?: string[];
  t: (key: string) => string;
}) {
  const { request } = useApiClient();
  const [items, setItems] = useState<OrganizationMember[] | null>(null);
  const trimmed = query.trim();

  useEffect(() => {
    let cancelled = false;
    const task = window.setTimeout(async () => {
      if (trimmed.length < 2) { setItems(null); return; }
      try {
        const found = await suggestOrganizationUsersApi(request, trimmed);
        if (!cancelled) setItems(found);
      } catch {
        // Suggestions are a convenience; a failed lookup stays silent.
        if (!cancelled) setItems([]);
      }
    }, trimmed.length < 2 ? 0 : 250);
    return () => { cancelled = true; window.clearTimeout(task); };
  }, [trimmed, request]);

  const displayItems = useMemo(() => {
    if (!items) return null;
    const lowerExcluded = new Set(excludedEmails.map(e => e.toLowerCase()));
    return items.filter(m => !lowerExcluded.has(m.email.toLowerCase()));
  }, [items, excludedEmails]);

  if (trimmed.length < 2) return null;
  return (
    <div className="absolute z-40 top-full mt-1 w-full overflow-hidden rounded-lg border border-border bg-popover shadow-md">
      {displayItems === null ? (
        <p className="px-3 py-2 text-sm text-muted-foreground">{t("suggestLoading")}</p>
      ) : displayItems.length === 0 ? (
        <p className="px-3 py-2 text-sm text-muted-foreground">{t("suggestEmpty")}</p>
      ) : (
        displayItems.map(member => (
          <button
            key={member.id}
            type="button"
            className="flex w-full items-center gap-3 px-3 py-2 text-left outline-none transition-colors hover:bg-muted focus-visible:bg-muted"
            onMouseDown={event => {
              event.preventDefault();
              onPick(member);
            }}
          >
            <Avatar className="size-7 shrink-0">
              <AvatarFallback className="bg-primary/15 text-[10px] text-primary">
                {initials(member.fullName || member.email)}
              </AvatarFallback>
            </Avatar>
            <span className="min-w-0">
              <span className="block truncate text-sm font-medium text-foreground">
                {member.fullName || t("unnamed")}
              </span>
              <span className="block truncate text-xs text-muted-foreground">{member.email}</span>
            </span>
          </button>
        ))
      )}
    </div>
  );
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
  const pagedMembers = usePagedList(directory.members);
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

  const [selectedMembers, setSelectedMembers] = useState<SelectedMember[]>([]);
  const [typedEmail, setTypedEmail] = useState("");
  const [suggestOpen, setSuggestOpen] = useState(false);
  const [addMemberSubmitting, setAddMemberSubmitting] = useState(false);
  const [validationError, setValidationError] = useState<string | null>(null);
  const suggestCloseTimer = useRef<number | null>(null);
  const addMemberInputRef = useRef<HTMLInputElement | null>(null);

  const addSelectedMember = useCallback((member: SelectedMember) => {
    setSelectedMembers(current => {
      if (current.some(m => m.email.toLowerCase() === member.email.toLowerCase())) {
        return current;
      }
      return [...current, member];
    });
    setValidationError(null);
  }, []);

  const removeSelectedMember = useCallback((email: string) => {
    setSelectedMembers(current => current.filter(m => m.email.toLowerCase() !== email.toLowerCase()));
  }, []);

  const handleEmailInput = (value: string) => {
    setValidationError(null);
    if (/[\s,;]/.test(value)) {
      const extracted = extractValidEmails(value);
      if (extracted.length > 0) {
        for (const email of extracted) {
          addSelectedMember({ email });
        }
        const parts = value.split(/[\s,;]+/);
        const last = parts[parts.length - 1];
        if (last && !EMAIL_REGEX.test(last)) {
          setTypedEmail(last);
        } else {
          setTypedEmail("");
        }
        setSuggestOpen(false);
        return;
      }
    }
    setTypedEmail(value);
    setSuggestOpen(true);
  };

  const handleKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter" || event.key === ",") {
      event.preventDefault();
      const trimmed = typedEmail.trim();
      if (trimmed) {
        if (EMAIL_REGEX.test(trimmed)) {
          addSelectedMember({ email: trimmed });
          setTypedEmail("");
          setSuggestOpen(false);
        } else {
          setValidationError(t("validEmail"));
        }
      }
    } else if (event.key === "Backspace" && typedEmail === "" && selectedMembers.length > 0) {
      removeSelectedMember(selectedMembers[selectedMembers.length - 1].email);
    }
  };

  const onAddMembers = async (event: React.FormEvent) => {
    event.preventDefault();
    setValidationError(null);

    const toAdd = [...selectedMembers];
    const pendingText = typedEmail.trim();
    if (pendingText) {
      const extracted = extractValidEmails(pendingText);
      if (extracted.length > 0) {
        for (const email of extracted) {
          if (!toAdd.some(m => m.email.toLowerCase() === email.toLowerCase())) {
            toAdd.push({ email });
          }
        }
        setTypedEmail("");
      } else if (toAdd.length === 0) {
        setValidationError(t("validEmail"));
        return;
      }
    }

    if (toAdd.length === 0) {
      setValidationError(t("validEmail"));
      return;
    }

    setAddMemberSubmitting(true);
    try {
      const results = await Promise.allSettled(
        toAdd.map(m => addMemberToWorkspaceApi(request, m.email, addMemberRole))
      );
      const succeeded = results.filter(r => r.status === "fulfilled").length;
      const failed = results.filter(r => r.status === "rejected") as PromiseRejectedResult[];

      if (failed.length === 0) {
        toast.success(succeeded === 1 ? t("memberAdded") : t("membersAddedCount", { count: succeeded }));
        setSelectedMembers([]);
        setTypedEmail("");
        setAddMemberRole("MEMBER");
        setSuggestOpen(false);
        setShowAddMember(false);
        await loadDirectory();
      } else if (succeeded > 0) {
        toast.success(t("membersAddedCount", { count: succeeded }));
        toast.error(failed[0].reason?.message || t("fallback.addMember"));
        const failedEmails = failed.map((_, i) => toAdd[results.findIndex((r, idx) => r.status === "rejected" && idx >= i)].email);
        setSelectedMembers(toAdd.filter(m => failedEmails.includes(m.email)));
        await loadDirectory();
      } else {
        toast.error(failed[0].reason?.message || t("fallback.addMember"));
      }
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : t("fallback.addMember"));
    } finally {
      setAddMemberSubmitting(false);
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

  return <div className="space-y-6">
    <div className="flex items-center justify-between gap-4">
      <div>
        <h2 className="text-xl font-semibold text-foreground">{t("title")}</h2>
        <p className="mt-1 text-sm text-muted-foreground">{t("description")}</p>
      </div>
      <div className="flex items-center gap-2">
        {canAddMember && (
          <Button
            size="sm"
            onClick={() => {
              setShowAddMember(true);
              setSelectedMembers([]);
              setAddMemberRole("MEMBER");
              setTypedEmail("");
              setValidationError(null);
            }}
            className="gap-1.5"
          >
            <UserPlus className="size-4" />
            {t("addToWorkspace")}
          </Button>
        )}
      </div>
    </div>

    {error && (
      <div role="alert" className="flex items-center justify-between rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
        <span className="flex items-center gap-2"><AlertCircle className="size-4" />{error}</span>
        <Button size="sm" variant="outline" onClick={() => void loadDirectory()}>{t("retry")}</Button>
      </div>
    )}

    <Card>
      {loading ? (
        <TableSkeleton />
      ) : directory.members.length === 0 ? (
        <CardContent className="py-16 text-center">
          <Users className="mx-auto mb-3 size-10 text-muted-foreground opacity-50" />
          <p className="font-medium text-muted-foreground">{t("empty")}</p>
          {canAddMember && <p className="mt-1 text-sm text-muted-foreground">{t("emptyDescription")}</p>}
        </CardContent>
      ) : (
        <>
        <div className="divide-y divide-border sm:hidden">
          {pagedMembers.slice.map(member => {
            const finalAdmin = member.workspaceRole === "WORKSPACE_ADMIN" && member.status === "ACTIVE" && activeAdminCount === 1;
            const protectedAction = member.currentUser || finalAdmin;
            return (
              <div key={`member-mobile-${member.id}`} className="p-3">
                <div className="flex items-center gap-3">
                  <Avatar className="size-8 shrink-0">
                    <AvatarFallback className="bg-primary/15 text-xs text-primary">
                      {initials(member.fullName || member.email)}
                    </AvatarFallback>
                  </Avatar>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium text-foreground">
                      {member.fullName || t("unnamed")}
                      {member.currentUser && <span className="ml-1.5 text-xs font-normal text-muted-foreground">{t("you")}</span>}
                    </p>
                    <p className="truncate text-xs text-muted-foreground">{member.email}</p>
                  </div>
                  <StatusBadge status={member.status} />
                </div>
                <div className="mt-2.5 flex items-center justify-between gap-2">
                  {isAdmin ? (
                    <Select
                      value={member.workspaceRole}
                      disabled={busyId === member.id || protectedAction}
                      onValueChange={value => void changeRole(member, value as WorkspaceRole)}
                    >
                      <SelectTrigger className="w-36"><SelectValue>{roleLabel(member.workspaceRole)}</SelectValue></SelectTrigger>
                      <SelectContent>
                        <SelectItem value="WORKSPACE_ADMIN">{t("roles.admin")}</SelectItem>
                        <SelectItem value="MEMBER">{t("roles.user")}</SelectItem>
                      </SelectContent>
                    </Select>
                  ) : (
                    <span className="text-xs text-muted-foreground">{roleLabel(member.workspaceRole)}</span>
                  )}
                  {isAdmin && canRemoveMember && (
                    <Button size="sm" variant="outline" disabled={busyId === member.id || protectedAction} title={t("removeFromWorkspaceHint")} onClick={() => setRemoveTarget(member)}>
                      <UserMinus className="size-3.5" />
                      {t("removeFromWorkspace")}
                    </Button>
                  )}
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
              <TableHead>{t("table.role")}</TableHead>
              <TableHead>{t("table.status")}</TableHead>
              <TableHead className="hidden sm:table-cell">{t("table.joinedExpires")}</TableHead>
              {isAdmin && <TableHead className="text-right">{t("table.actions")}</TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {pagedMembers.slice.map(member => {
              const finalAdmin = member.workspaceRole === "WORKSPACE_ADMIN" && member.status === "ACTIVE" && activeAdminCount === 1;
              const protectedAction = member.currentUser || finalAdmin;
              return (
                <TableRow key={`member-${member.id}`}>
                  <TableCell>
                    <div className="flex items-center gap-3">
                      <Avatar className="size-9">
                        <AvatarFallback className="bg-primary/15 text-xs text-primary">
                          {initials(member.fullName || member.email)}
                        </AvatarFallback>
                      </Avatar>
                      <div className="min-w-0">
                        <p className="truncate text-sm font-medium text-foreground">
                          {member.fullName || t("unnamed")}
                          {member.currentUser && <span className="ml-2 text-xs font-normal text-muted-foreground">{t("you")}</span>}
                        </p>
                        <p className="max-w-44 truncate text-sm text-muted-foreground sm:max-w-none">{member.email}</p>
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
                        <SelectTrigger className="w-52"><SelectValue>{roleLabel(member.workspaceRole)}</SelectValue></SelectTrigger>
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
                  <TableCell className="hidden text-sm text-muted-foreground sm:table-cell">{formatDate(member.joinedAt)}</TableCell>
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
                          <UserMinus className="size-3.5" />
                          {t("removeFromWorkspace")}
                        </Button>
                      )}
                    </TableCell>
                  )}
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
        </div>
        <ListPager
          page={pagedMembers.page}
          pageCount={pagedMembers.pageCount}
          size={pagedMembers.size}
          total={pagedMembers.total}
          onPageChange={pagedMembers.setPage}
          onSizeChange={(nextSize) => { pagedMembers.setSize(nextSize); pagedMembers.setPage(1); }}
        />
        </>
      )}
    </Card>

    {/* Add Member to Workspace Dialog */}
    <Dialog open={showAddMember} onOpenChange={open => {
      setShowAddMember(open);
      if (!open) {
        setSelectedMembers([]);
        setTypedEmail("");
        setValidationError(null);
        setSuggestOpen(false);
      }
    }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("addToWorkspaceTitle")}</DialogTitle>
          <DialogDescription>{t("existingMemberHint")}</DialogDescription>
        </DialogHeader>
        <form onSubmit={onAddMembers} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="addMemberEmail">{t("emailAddress")}</Label>
            <div className="relative">
              <div
                className="min-h-10 flex flex-wrap items-center gap-1.5 rounded-lg border border-input bg-background p-1.5 focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2"
                onClick={() => addMemberInputRef.current?.focus()}
              >
                {selectedMembers.map(member => (
                  <Badge
                    key={member.email}
                    variant="secondary"
                    className="flex h-7 items-center gap-1.5 rounded-md px-2 py-0.5 text-xs font-normal"
                  >
                    <Avatar className="size-4 shrink-0">
                      <AvatarFallback className="bg-primary/20 text-[9px] text-primary">
                        {initials(member.fullName || member.email)}
                      </AvatarFallback>
                    </Avatar>
                    <span className="truncate max-w-[200px]" title={member.email}>
                      {member.fullName ? `${member.fullName} (${member.email})` : member.email}
                    </span>
                    <button
                      type="button"
                      className="ml-0.5 rounded-sm p-0.5 opacity-70 hover:opacity-100 hover:bg-muted focus:outline-none"
                      onClick={event => {
                        event.stopPropagation();
                        removeSelectedMember(member.email);
                      }}
                      aria-label={`Remove ${member.email}`}
                    >
                      <X className="size-3" />
                    </button>
                  </Badge>
                ))}
                <input
                  ref={addMemberInputRef}
                  id="addMemberEmail"
                  type="text"
                  autoComplete="off"
                  value={typedEmail}
                  placeholder={selectedMembers.length === 0 ? "colleague@company.com" : t("addMorePlaceholder")}
                  className="flex-1 min-w-[140px] bg-transparent text-sm outline-none placeholder:text-muted-foreground"
                  onChange={event => handleEmailInput(event.target.value)}
                  onKeyDown={handleKeyDown}
                  onFocus={() => {
                    if (suggestCloseTimer.current) {
                      window.clearTimeout(suggestCloseTimer.current);
                      suggestCloseTimer.current = null;
                    }
                    setSuggestOpen(true);
                  }}
                  onBlur={() => {
                    suggestCloseTimer.current = window.setTimeout(() => setSuggestOpen(false), 200);
                  }}
                />
              </div>
              {suggestOpen && (
                <MemberSuggestions
                  query={typedEmail}
                  excludedEmails={[
                    ...directory.members.map(m => m.email),
                    ...selectedMembers.map(m => m.email),
                  ]}
                  t={t as (key: string) => string}
                  onPick={member => {
                    addSelectedMember({ email: member.email, fullName: member.fullName, id: member.id });
                    setTypedEmail("");
                    setSuggestOpen(false);
                    addMemberInputRef.current?.focus();
                  }}
                />
              )}
            </div>
            {validationError && (
              <p className="text-xs text-destructive">{validationError}</p>
            )}
            <p className="text-xs text-muted-foreground">{t("addMultipleMembersHint")}</p>
          </div>
          <div className="space-y-1.5">
            <Label>{t("role")}</Label>
            <Select
              value={addMemberRole}
              disabled={!isAdmin}
              onValueChange={value => setAddMemberRole(value as WorkspaceRole)}
            >
              {/* The trigger renders the translated label, never the raw enum. */}
              <SelectTrigger><SelectValue>{roleLabel(addMemberRole)}</SelectValue></SelectTrigger>
              <SelectContent>
                {isAdmin && <SelectItem value="WORKSPACE_ADMIN">{t("roles.admin")}</SelectItem>}
                <SelectItem value="MEMBER">{t("roles.user")}</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setShowAddMember(false)}>{t("cancel")}</Button>
            <Button type="submit" disabled={addMemberSubmitting}>
              {addMemberSubmitting && <Loader2 className="mr-2 size-4 animate-spin" />}
              {selectedMembers.length > 1
                ? `${t("addMember")} (${selectedMembers.length})`
                : t("addMember")}
            </Button>
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
