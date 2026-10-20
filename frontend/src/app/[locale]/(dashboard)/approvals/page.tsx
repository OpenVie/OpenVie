"use client";

import { useCallback, useEffect, useState } from "react";
import { useFormatter, useTranslations } from "next-intl";
import toast from "react-hot-toast";
import { Check, ClipboardCheck, Loader2, X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { useApiClient } from "@/hooks/useApiClient";
import {
  claimApprovalTaskApi,
  completeApprovalTaskApi,
  listApprovalProcessesApi,
  listApprovalTasksApi,
} from "@/lib/workflow-api";
import type { ApprovalDecision, WorkflowProcess, WorkflowTask } from "@/types";

type DecisionDialog = { task: WorkflowTask; decision: "REJECT" | "ESCALATE" } | null;

function taskRole(task: WorkflowTask, t: ReturnType<typeof useTranslations<"Approval">>) {
  return task.approvalLevel === 2 ? t("levelTwo") : t("levelOne");
}

export default function ApprovalsPage() {
  const t = useTranslations("Approval");
  const format = useFormatter();
  const { request } = useApiClient();
  const [processes, setProcesses] = useState<WorkflowProcess[]>([]);
  const [assigned, setAssigned] = useState<WorkflowTask[]>([]);
  const [groupTasks, setGroupTasks] = useState<WorkflowTask[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyTaskId, setBusyTaskId] = useState<string | null>(null);
  const [dialog, setDialog] = useState<DecisionDialog>(null);
  const [reason, setReason] = useState("");

  const load = useCallback(async () => {
    const [nextProcesses, nextTasks] = await Promise.all([
      listApprovalProcessesApi(request, true),
      listApprovalTasksApi(request),
    ]);
    setProcesses(nextProcesses);
    setAssigned(nextTasks.assignedToMe);
    setGroupTasks(nextTasks.groupTasks);
  }, [request]);

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

  const tasks = [...assigned, ...groupTasks.filter((task) => !assigned.some((item) => item.taskId === task.taskId))];

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
              {tasks.length === 0 ? <p className="text-sm text-muted-foreground">{t("emptyInbox")}</p> : tasks.map((task) => (
                <div key={task.taskId} className="rounded-xl border p-4">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="truncate font-medium">{task.documentFileName}</p>
                      <p className="mt-1 text-xs text-muted-foreground">{taskRole(task, t)} · {format.dateTime(new Date(task.createdAt), { dateStyle: "medium", timeStyle: "short" })}</p>
                    </div>
                    <Badge variant="secondary">{task.assignee ? t("claimed") : t("unclaimed")}</Badge>
                  </div>
                  <div className="mt-3 flex flex-wrap gap-2">
                    {!task.assignee && (
                      <Button size="sm" variant="outline" disabled={busyTaskId === task.taskId} onClick={() => runTaskAction(task, () => claimApprovalTaskApi(request, task.taskId))}>
                        {busyTaskId === task.taskId && <Loader2 className="animate-spin" />} {t("claim")}
                      </Button>
                    )}
                    {(task.assignee || !task.assignee) && (
                      <>
                        <Button size="sm" disabled={busyTaskId === task.taskId || !task.assignee} onClick={() => complete(task, "APPROVE")}>
                          <Check /> {t("approve")}
                        </Button>
                        <Button size="sm" variant="destructive" disabled={busyTaskId === task.taskId || !task.assignee} onClick={() => openDecision(task, "REJECT")}>
                          <X /> {t("reject")}
                        </Button>
                        {task.approvalLevel === 1 && (
                          <Button size="sm" variant="outline" disabled={busyTaskId === task.taskId || !task.assignee} onClick={() => openDecision(task, "ESCALATE")}>
                            {t("escalate")}
                          </Button>
                        )}
                      </>
                    )}
                  </div>
                </div>
              ))}
            </CardContent>
          </Card>

          <Card>
            <CardHeader><CardTitle>{t("submissionsTitle")}</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              {processes.length === 0 ? <p className="text-sm text-muted-foreground">{t("emptySubmissions")}</p> : processes.map((process) => (
                <div key={process.processInstanceId} className="flex items-center justify-between gap-3 rounded-xl border p-4">
                  <div className="min-w-0">
                    <p className="truncate font-medium">{process.documentFileName}</p>
                    <p className="mt-1 text-xs text-muted-foreground">{format.dateTime(new Date(process.startedAt), { dateStyle: "medium", timeStyle: "short" })}</p>
                  </div>
                  <Badge variant={process.documentStatus === "APPROVED" ? "default" : process.documentStatus === "REJECTED" ? "destructive" : "secondary"}>
                    {process.documentStatus}
                  </Badge>
                </div>
              ))}
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
    </main>
  );
}
