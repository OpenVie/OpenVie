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

import type {
  ApprovalDecision,
  CompleteTaskResponse,
  ProcessLog,
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

/**
 * Server-paged window over the approval journal: 1-based FE page mapped to
 * the 0-based `page` query, total read from the X-Total-Count header — same
 * contract as the documents list.
 */
export async function listApprovalProcessesPagedApi(
  request: ApiRequest,
  query: { includeFinished?: boolean; page?: number; size?: number },
): Promise<{ items: WorkflowProcess[]; total: number }> {
  const params = new URLSearchParams({
    includeFinished: String(query.includeFinished ?? true),
    page: String(query.page ?? 0),
    size: String(query.size ?? 20),
  });
  const response = await request(`${getApiBase()}/workflow/processes?${params}`);
  const items = await readJsonOrThrow<WorkflowProcess[]>(response);
  return { items, total: Number(response.headers.get("X-Total-Count") ?? items.length) };
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

/** The audited trail of one approval process: who did what, and when. */
export async function getProcessLogApi(
  request: ApiRequest,
  processInstanceId: string,
): Promise<ProcessLog> {
  const response = await request(`${getApiBase()}/workflow/processes/${processInstanceId}/log`);
  return readJsonOrThrow<ProcessLog>(response);
}
