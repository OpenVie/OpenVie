import type {
  OrganizationSnapshot,
  TenantWorkspace,
  WorkspaceMemberView,
  WorkspaceRole,
  WorkspaceView,
  WorkspaceVisibility,
} from "@/types";
import { getApiBase } from "@/lib/auth-api";
import { parseApiError, readJsonOrThrow } from "@/lib/api-error";
import type { ApiRequest } from "@/lib/documents-api";

/** The active workspace's knowledge base + chatbot (chat/documents surface). */
export async function getTenantWorkspaceApi(
  request: ApiRequest,
): Promise<TenantWorkspace> {
  const res = await request(`${getApiBase()}/tenants/me/workspace`);
  return readJsonOrThrow<TenantWorkspace>(res);
}

// ─── Organization-owner workspace administration (/workspaces) ───────────────

export async function listWorkspacesApi(request: ApiRequest): Promise<WorkspaceView[]> {
  return readJsonOrThrow<WorkspaceView[]>(await request(`${getApiBase()}/workspaces`));
}

export async function createWorkspaceApi(
  request: ApiRequest,
  name: string,
  visibility: WorkspaceVisibility,
): Promise<WorkspaceView> {
  return readJsonOrThrow<WorkspaceView>(await request(`${getApiBase()}/workspaces`, {
    method: "POST",
    body: JSON.stringify({ name, visibility }),
  }));
}

export async function renameWorkspaceApi(
  request: ApiRequest,
  workspaceId: string,
  name: string,
): Promise<WorkspaceView> {
  return readJsonOrThrow<WorkspaceView>(await request(`${getApiBase()}/workspaces/${workspaceId}`, {
    method: "PATCH",
    body: JSON.stringify({ name }),
  }));
}

export async function setWorkspaceVisibilityApi(
  request: ApiRequest,
  workspaceId: string,
  visibility: WorkspaceVisibility,
): Promise<WorkspaceView> {
  return readJsonOrThrow<WorkspaceView>(
    await request(`${getApiBase()}/workspaces/${workspaceId}/visibility`, {
      method: "PATCH",
      body: JSON.stringify({ visibility }),
    }),
  );
}

export async function archiveWorkspaceApi(
  request: ApiRequest,
  workspaceId: string,
): Promise<void> {
  const response = await request(`${getApiBase()}/workspaces/${workspaceId}`, {
    method: "DELETE",
  });
  if (!response.ok) throw await parseApiError(response);
}

export async function listWorkspaceMembersApi(
  request: ApiRequest,
  workspaceId: string,
): Promise<WorkspaceMemberView[]> {
  return readJsonOrThrow<WorkspaceMemberView[]>(
    await request(`${getApiBase()}/workspaces/${workspaceId}/members`),
  );
}

export async function addWorkspaceMemberApi(
  request: ApiRequest,
  workspaceId: string,
  email: string,
  role: WorkspaceRole,
): Promise<WorkspaceMemberView> {
  return readJsonOrThrow<WorkspaceMemberView>(
    await request(`${getApiBase()}/workspaces/${workspaceId}/members`, {
      method: "POST",
      body: JSON.stringify({ email, role }),
    }),
  );
}

export async function removeWorkspaceMemberApi(
  request: ApiRequest,
  workspaceId: string,
  userId: string,
): Promise<void> {
  const response = await request(
    `${getApiBase()}/workspaces/${workspaceId}/members/${userId}`,
    { method: "DELETE" },
  );
  if (!response.ok) throw await parseApiError(response);
}

export async function getOrganizationApi(request: ApiRequest): Promise<OrganizationSnapshot> {
  return readJsonOrThrow<OrganizationSnapshot>(
    await request(`${getApiBase()}/workspaces/organization`),
  );
}

export async function setSelfRegistrationApi(
  request: ApiRequest,
  allowed: boolean,
): Promise<{ allowed: boolean }> {
  return readJsonOrThrow<{ allowed: boolean }>(
    await request(`${getApiBase()}/workspaces/organization/self-registration`, {
      method: "PATCH",
      body: JSON.stringify({ allowed }),
    }),
  );
}
