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
import { Check, ClipboardCheck, Eye, History, Loader2, X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { useApiClient } from "@/hooks/useApiClient";
import { useAuthStore } from "@/components/providers/StoreProvider";
import DocumentPreview from "@/components/documents/DocumentPreview";
import { ListPager, usePagedList } from "@/components/ui/list-pager";
import { safePage, safeSize, useUrlListState } from "@/hooks/useUrlListState";
import {
  claimApprovalTaskApi,
  completeApprovalTaskApi,
  getProcessLogApi,
  listApprovalProcessesPagedApi,
  listApprovalTasksApi,
} from "@/lib/workflow-api";
import type {
  ApprovalDecision,
  ProcessLog,
  WorkflowProcess,
  WorkflowTask,
} from "@/types";

type DecisionDialog = { task: WorkflowTask; decision: "REJECT" | "ESCALATE" } | null;
type LogDialog = { process: WorkflowProcess } | null;
type PreviewDialog = { documentId: string; fileName: string } | null;

function taskRole(task: WorkflowTask, t: ReturnType<typeof useTranslations<"Approval">>) {
  return task.approvalLevel === 2 ? t("levelTwo") : t("levelOne");
}

export default function ApprovalsPage() {
  const t = useTranslations("Approval");
  const format = useFormatter();
  const { request } = useApiClient();
  const user = useAuthStore((state) => state.user);
  const { searchParams, update } = useUrlListState();
  const page = safePage(searchParams.get("page"));
  const size = safeSize(searchParams.get("size"));
  const [processes, setProcesses] = useState<WorkflowProcess[]>([]);
  const [processTotal, setProcessTotal] = useState(0);
  const journalRequestRef = useRef("");
  const [assigned, setAssigned] = useState<WorkflowTask[]>([]);
  const [groupTasks, setGroupTasks] = useState<WorkflowTask[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyTaskId, setBusyTaskId] = useState<string | null>(null);
  const [dialog, setDialog] = useState<DecisionDialog>(null);
  const [reason, setReason] = useState("");
  const [logDialog, setLogDialog] = useState<LogDialog>(null);
  const [log, setLog] = useState<ProcessLog | null>(null);
  const [logLoading, setLogLoading] = useState(false);
  const [preview, setPreview] = useState<PreviewDialog>(null);

  const load = useCallback(async () => {
    const requestKey = `${page}/${size}`;
    journalRequestRef.current = requestKey;
    const [journal, nextTasks] = await Promise.all([
      listApprovalProcessesPagedApi(request, { includeFinished: true, page: page - 1, size }),
      listApprovalTasksApi(request),
    ]);
    // Stale guard: a slow response for an earlier window must not overwrite
    // the page the user has already navigated to.
    if (journalRequestRef.current !== requestKey) return;
    const pages = Math.max(1, Math.ceil(journal.total / size));
    if (page > pages) {
      update({ page: pages === 1 ? null : pages }, false);
      return;
    }
    setProcesses(journal.items);
    setProcessTotal(journal.total);
    setAssigned(nextTasks.assignedToMe);
    setGroupTasks(nextTasks.groupTasks);
  }, [request, page, size, update]);

  useEffect(() => {
    let cancelled = false;
    const timer = window.setTimeout(() => {
      load().catch((error) => {
        if (!cancelled) toast.error(error instanceof Error ? error.message : t("fallback.load"));
      }).finally(() => {
        if (!cancelled) setLoading(false);
      });
    }, 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [load, t]);

  async function runTaskAction(task: WorkflowTask, action: () => Promise<unknown>) {
    setBusyTaskId(task.taskId);
    try {
      await action();
      await load();
      toast.success(t("updated"));
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("fallback.action"));
    } finally {
      setBusyTaskId(null);
    }
  }

  function openDecision(task: WorkflowTask, decision: "REJECT" | "ESCALATE") {
    setReason("");
    setDialog({ task, decision });
  }

  async function complete(task: WorkflowTask, decision: ApprovalDecision, decisionReason?: string) {
    await runTaskAction(task, () => completeApprovalTaskApi(request, task.taskId, decision, decisionReason));
  }

  async function openLog(process: WorkflowProcess) {
    setLogDialog({ process });
    setLog(null);
    setLogLoading(true);
    try {
      setLog(await getProcessLogApi(request, process.processInstanceId));
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("fallback.log"));
      setLogDialog(null);
    } finally {
      setLogLoading(false);
    }
  }

  const tasks = [...assigned, ...groupTasks.filter((task) => !assigned.some((item) => item.taskId === task.taskId))];
  const pagedTasks = usePagedList(tasks);
  const journalPageCount = Math.max(1, Math.ceil(processTotal / size));

  function statusLabel(status: string): string {
    const key: Record<string, Parameters<typeof t>[0]> = {
      IN_REVIEW: "status.inReview",
      APPROVED: "status.approved",
      REJECTED: "status.rejected",
    };
    const message = key[status];
    return message && t.has(message) ? t(message) : status;
  }

  function actionLabel(action: string): string {
    const key = `action.${action}` as Parameters<typeof t>[0];
    return t.has(key) ? t(key) : action;
  }

  function entryExtras(metadata: Record<string, unknown>): string | null {
    const decision = typeof metadata.decision === "string" ? metadata.decision : null;
    const reason = typeof metadata.reason === "string" && metadata.reason ? `“${metadata.reason}”` : null;
    const selfDecision = metadata.selfDecision === true;
    const selfApproved = metadata.selfApproved === true;
    const flags: string[] = [];
    if (reason) flags.push(`${t("reasonLabel")}: ${reason}`);
    if (selfDecision || selfApproved) flags.push(t("selfDecision"));
    if (decision) flags.unshift(decision);
    return flags.length ? flags.join(" · ") : null;
  }

  const logEntries = useMemo(() => log?.entries ?? [], [log]);

  return (
    <main className="ov-page-shell">
      <div className="mb-6 flex items-start gap-3">
        <ClipboardCheck className="mt-1 size-7 text-primary" />
        <div>
          <p className="ov-process-eyebrow text-xs font-semibold uppercase tracking-wider text-primary">[P] {t("eyebrow")}</p>
          <h1 className="text-2xl font-semibold tracking-tight">{t("title")}</h1>
          <p className="mt-1 text-sm text-muted-foreground">{t("description")}</p>
        </div>
      </div>

      {loading ? (
        <div className="flex items-center gap-2 text-sm text-muted-foreground"><Loader2 className="size-4 animate-spin" />{t("loading")}</div>
      ) : (
        <div className="grid gap-6 xl:grid-cols-[1.1fr_1fr]">
          <Card>
            <CardHeader><CardTitle>{t("inboxTitle")}</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              {tasks.length === 0 ? <p className="text-sm text-muted-foreground">{t("emptyInbox")}</p> : pagedTasks.slice.map((task) => {
                const mine = Boolean(user?.userId && task.assignee === user.userId);
                return (
                <div key={task.taskId} className="rounded-xl border p-4">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="truncate font-medium">{task.documentFileName}</p>
                      <p className="mt-1 text-xs text-muted-foreground">{taskRole(task, t)} · {format.dateTime(new Date(task.createdAt), { dateStyle: "medium", timeStyle: "short" })}</p>
                    </div>
                    <div className="flex shrink-0 items-center gap-1">
                      <Button size="icon" variant="ghost" className="size-8" aria-label={t("viewDocument")} title={t("viewDocument")} onClick={() => setPreview({ documentId: task.documentId, fileName: task.documentFileName })}>
                        <Eye className="size-4" />
                      </Button>
                      <Badge variant="secondary">{task.assignee ? t("claimed") : t("unclaimed")}</Badge>
                    </div>
                  </div>
                  <div className="mt-3 flex flex-wrap gap-2">
                    {!task.assignee && (
                      <Button size="sm" variant="outline" disabled={busyTaskId === task.taskId} onClick={() => runTaskAction(task, () => claimApprovalTaskApi(request, task.taskId))}>
                        {busyTaskId === task.taskId && <Loader2 className="animate-spin" />} {t("claim")}
                      </Button>
                    )}
                    {mine && (
                      <>
                        <Button size="sm" disabled={busyTaskId === task.taskId} onClick={() => complete(task, "APPROVE")}>
                          <Check /> {t("approve")}
                        </Button>
                        <Button size="sm" variant="destructive" disabled={busyTaskId === task.taskId} onClick={() => openDecision(task, "REJECT")}>
                          <X /> {t("reject")}
                        </Button>
                        {task.approvalLevel === 1 && (
                          <Button size="sm" variant="outline" disabled={busyTaskId === task.taskId} onClick={() => openDecision(task, "ESCALATE")}>
                            {t("escalate")}
                          </Button>
                        )}
                      </>
                    )}
                  </div>
                </div>
                );
              })}
              <ListPager
                page={pagedTasks.page}
                pageCount={pagedTasks.pageCount}
                size={pagedTasks.size}
                total={pagedTasks.total}
                onPageChange={pagedTasks.setPage}
                onSizeChange={(nextSize) => { pagedTasks.setSize(nextSize); pagedTasks.setPage(1); }}
                className="-mx-2"
              />
            </CardContent>
          </Card>

          <Card>
            <CardHeader><CardTitle>{t("submissionsTitle")}</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              {processes.length === 0 ? <p className="text-sm text-muted-foreground">{t("emptySubmissions")}</p> : processes.map((process) => (
                <div key={process.processInstanceId} className="flex items-center justify-between gap-3 rounded-xl border p-4">
                  <div className="min-w-0">
                    <p className="truncate font-medium">{process.documentFileName}</p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {statusLabel(process.documentStatus)} · {format.dateTime(new Date(process.startedAt), { dateStyle: "medium", timeStyle: "short" })}
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    <Badge variant={process.documentStatus === "APPROVED" ? "default" : process.documentStatus === "REJECTED" ? "destructive" : "secondary"}>
                      {statusLabel(process.documentStatus)}
                    </Badge>
                    <Button size="icon" variant="ghost" className="size-8" aria-label={t("viewDocument")} title={t("viewDocument")} onClick={() => setPreview({ documentId: process.documentId, fileName: process.documentFileName })}>
                      <Eye className="size-4" />
                    </Button>
                    <Button size="icon" variant="ghost" className="size-8" aria-label={t("viewLog")} onClick={() => openLog(process)}>
                      <History className="size-4" />
                    </Button>
                  </div>
                </div>
              ))}
              <ListPager
                page={page}
                pageCount={journalPageCount}
                size={size}
                total={processTotal}
                onPageChange={(value) => update({ page: value === 1 ? null : value }, false)}
                onSizeChange={(value) => update({ size: value === 20 ? null : value })}
                className="-mx-2"
              />
            </CardContent>
          </Card>
        </div>
      )}

      <Dialog open={dialog !== null} onOpenChange={(open) => !open && setDialog(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{dialog?.decision === "REJECT" ? t("rejectTitle") : t("escalateTitle")}</DialogTitle>
            <DialogDescription>{dialog?.task.documentFileName}</DialogDescription>
          </DialogHeader>
          <textarea
            className="min-h-24 w-full rounded-lg border bg-background p-3 text-sm outline-none focus:ring-2 focus:ring-ring"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder={dialog?.decision === "REJECT" ? t("rejectPlaceholder") : t("escalatePlaceholder")}
            maxLength={500}
          />
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialog(null)}>{t("cancel")}</Button>
            <Button
              variant={dialog?.decision === "REJECT" ? "destructive" : "default"}
              disabled={!dialog || busyTaskId === dialog.task.taskId || (dialog.decision === "REJECT" && !reason.trim())}
              onClick={async () => {
                if (!dialog) return;
                const current = dialog;
                setDialog(null);
                await complete(current.task, current.decision, reason);
              }}
            >
              {t("confirm")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={logDialog !== null} onOpenChange={(open) => !open && setLogDialog(null)}>
        <DialogContent className="max-h-[80vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{t("logTitle")}</DialogTitle>
            <DialogDescription>{logDialog?.process.documentFileName}</DialogDescription>
          </DialogHeader>
          {logLoading ? (
            <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
              <Loader2 className="size-4 animate-spin" /> {t("loading")}
            </div>
          ) : logEntries.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("logEmpty")}</p>
          ) : (
            <ol className="space-y-3">
              {logEntries.map((entry, index) => (
                <li key={`${entry.action}-${entry.occurredAt ?? index}`} className="rounded-lg border p-3 text-sm">
                  <div className="min-w-0">
                    <p className="font-medium">{actionLabel(entry.action)}</p>
                    <p className="text-xs text-muted-foreground">
                      {entry.actorName ?? entry.actorId ?? t("system")}
                      {entry.occurredAt
                        ? ` · ${format.dateTime(new Date(entry.occurredAt), { dateStyle: "medium", timeStyle: "medium" })}`
                        : ""}
                    </p>
                    {(() => {
                      const extras = entryExtras(entry.metadata ?? {});
                      return extras ? <p className="mt-1 text-xs text-muted-foreground">{extras}</p> : null;
                    })()}
                  </div>
                </li>
              ))}
            </ol>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setLogDialog(null)}>{t("cancel")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={preview !== null} onOpenChange={(open) => !open && setPreview(null)}>
        <DialogContent className="flex max-h-[85vh] flex-col overflow-hidden sm:max-w-4xl">
          <DialogHeader>
            <DialogTitle>{t("previewTitle")}</DialogTitle>
            <DialogDescription>{preview?.fileName}</DialogDescription>
          </DialogHeader>
          {preview && (
            <DocumentPreview
              documentId={preview.documentId}
              fileName={preview.fileName}
              className="h-[65vh] min-h-0 flex-1"
            />
          )}
        </DialogContent>
      </Dialog>
    </main>
  );
}
