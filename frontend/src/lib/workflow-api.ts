import type {
  ApprovalDecision,
  CompleteTaskResponse,
  PublishTarget,
  StartProcessResponse,
  WorkflowProcess,
  WorkflowTask,
  WorkspaceTasks,
} from "@/types";
import { getApiBase } from "@/lib/auth-api";
import { readJsonOrThrow } from "@/lib/api-error";
import type { ApiRequest } from "@/lib/api-request";

export type WorkflowProcessDetail = {
  process: WorkflowProcess;
  activeTasks: WorkflowTask[];
};

export async function startApprovalApi(
  request: ApiRequest,
  documentId: string,
  publishTarget: PublishTarget,
): Promise<StartProcessResponse> {
  const response = await request(`${getApiBase()}/workflow/processes`, {
    method: "POST",
    body: JSON.stringify({ documentId, publishTarget }),
  });
  return readJsonOrThrow<StartProcessResponse>(response);
}

export async function listApprovalProcessesApi(
  request: ApiRequest,
  includeFinished = true,
): Promise<WorkflowProcess[]> {
  const response = await request(
    `${getApiBase()}/workflow/processes?includeFinished=${includeFinished}`,
  );
  return readJsonOrThrow<WorkflowProcess[]>(response);
}

export async function listApprovalTasksApi(request: ApiRequest): Promise<WorkspaceTasks> {
  const response = await request(`${getApiBase()}/workflow/tasks`);
  return readJsonOrThrow<WorkspaceTasks>(response);
}

export async function claimApprovalTaskApi(
  request: ApiRequest,
  taskId: string,
): Promise<WorkflowTask> {
  const response = await request(`${getApiBase()}/workflow/tasks/${taskId}/claim`, {
    method: "POST",
  });
  return readJsonOrThrow<WorkflowTask>(response);
}

export async function completeApprovalTaskApi(
  request: ApiRequest,
  taskId: string,
  decision: ApprovalDecision,
  reason?: string,
): Promise<CompleteTaskResponse> {
  const response = await request(`${getApiBase()}/workflow/tasks/${taskId}/complete`, {
    method: "POST",
    body: JSON.stringify({ decision, reason: reason?.trim() || null }),
  });
  return readJsonOrThrow<CompleteTaskResponse>(response);
}
