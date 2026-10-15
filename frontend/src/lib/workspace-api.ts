import type {
  OrganizationSnapshot,
  TenantWorkspace,
  WorkspaceMemberView,
  WorkspaceRole,
  WorkspaceView,
  WorkspaceVisibility,
} from "@/types";
import { getAdminApiBase, getApiBase } from "@/lib/auth-api";
import { parseApiError, readJsonOrThrow } from "@/lib/api-error";
import type { ApiRequest } from "@/lib/documents-api";

/** The active workspace's knowledge base + chatbot (chat/documents surface). */
export async function getTenantWorkspaceApi(
  request: ApiRequest,
): Promise<TenantWorkspace> {
  const res = await request(`${getApiBase()}/tenants/me/workspace`);
  return readJsonOrThrow<TenantWorkspace>(res);
}

// ─── Organization-owner workspace administration (API: /api/v1/admin/workspaces) ─
// Soft admin boundary: these calls go through `/api/v1/admin/*` (backend
// alias, gateway-rate-limited separately). `getTenantWorkspaceApi` above stays
// on the canonical base — chat/documents are not admin-only.

export async function listWorkspacesApi(request: ApiRequest): Promise<WorkspaceView[]> {
  return readJsonOrThrow<WorkspaceView[]>(await request(`${getAdminApiBase()}/workspaces`));
}

export async function createWorkspaceApi(
  request: ApiRequest,
  name: string,
  visibility: WorkspaceVisibility,
): Promise<WorkspaceView> {
  return readJsonOrThrow<WorkspaceView>(await request(`${getAdminApiBase()}/workspaces`, {
    method: "POST",
    body: JSON.stringify({ name, visibility }),
  }));
}

export async function renameWorkspaceApi(
  request: ApiRequest,
  workspaceId: string,
  name: string,
): Promise<WorkspaceView> {
  return readJsonOrThrow<WorkspaceView>(await request(`${getAdminApiBase()}/workspaces/${workspaceId}`, {
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
    await request(`${getAdminApiBase()}/workspaces/${workspaceId}/visibility`, {
      method: "PATCH",
      body: JSON.stringify({ visibility }),
    }),
  );
}

export async function archiveWorkspaceApi(
  request: ApiRequest,
  workspaceId: string,
): Promise<void> {
  const response = await request(`${getAdminApiBase()}/workspaces/${workspaceId}`, {
    method: "DELETE",
  });
  if (!response.ok) throw await parseApiError(response);
}

export async function listWorkspaceMembersApi(
  request: ApiRequest,
  workspaceId: string,
): Promise<WorkspaceMemberView[]> {
  return readJsonOrThrow<WorkspaceMemberView[]>(
    await request(`${getAdminApiBase()}/workspaces/${workspaceId}/members`),
  );
}

export async function addWorkspaceMemberApi(
  request: ApiRequest,
  workspaceId: string,
  email: string,
  role: WorkspaceRole,
): Promise<WorkspaceMemberView> {
  return readJsonOrThrow<WorkspaceMemberView>(
    await request(`${getAdminApiBase()}/workspaces/${workspaceId}/members`, {
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
    `${getAdminApiBase()}/workspaces/${workspaceId}/members/${userId}`,
    { method: "DELETE" },
  );
  if (!response.ok) throw await parseApiError(response);
}

export async function getOrganizationApi(request: ApiRequest): Promise<OrganizationSnapshot> {
  return readJsonOrThrow<OrganizationSnapshot>(
    await request(`${getAdminApiBase()}/workspaces/organization`),
  );
}

export async function setSelfRegistrationApi(
  request: ApiRequest,
  allowed: boolean,
): Promise<{ allowed: boolean }> {
  return readJsonOrThrow<{ allowed: boolean }>(
    await request(`${getAdminApiBase()}/workspaces/organization/self-registration`, {
      method: "PATCH",
      body: JSON.stringify({ allowed }),
    }),
  );
}
