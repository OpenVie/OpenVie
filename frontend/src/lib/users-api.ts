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

import { getAdminApiBase, getApiBase } from "@/lib/auth-api";
import { parseApiError, readJsonOrThrow } from "@/lib/api-error";
import type { ApiRequest } from "@/lib/documents-api";
import type { OrganizationMember, TeamDirectory, TeamInvitation, TeamMember, UserStatus, WorkspaceRole } from "@/types";

export async function getTeamDirectory(request: ApiRequest): Promise<TeamDirectory> {
  return readJsonOrThrow<TeamDirectory>(await request(`${getApiBase()}/users/directory`));
}

export async function inviteTeamMember(
  request: ApiRequest,
  email: string,
  role: WorkspaceRole,
): Promise<TeamInvitation> {
  return readJsonOrThrow<TeamInvitation>(await request(`${getApiBase()}/users/invitations`, {
    method: "POST",
    body: JSON.stringify({ email, role }),
  }));
}

export async function resendTeamInvitation(
  request: ApiRequest,
  id: string,
): Promise<TeamInvitation> {
  return readJsonOrThrow<TeamInvitation>(
    await request(`${getApiBase()}/users/invitations/${id}/resend`, { method: "POST" }),
  );
}

export async function cancelTeamInvitation(request: ApiRequest, id: string): Promise<void> {
  const response = await request(`${getApiBase()}/users/invitations/${id}`, { method: "DELETE" });
  if (!response.ok) throw await parseApiError(response);
}

export async function updateTeamMemberRole(
  request: ApiRequest,
  id: string,
  role: WorkspaceRole,
): Promise<TeamMember> {
  return readJsonOrThrow<TeamMember>(await request(`${getApiBase()}/users/${id}/role`, {
    method: "PATCH",
    body: JSON.stringify({ role }),
  }));
}

/**
 * Workspace admin removes one member from their own active workspace. The
 * account stays in the organization; the default workspace (General) refuses
 * removal server-side.
 */
export async function removeWorkspaceMemberApi(
  request: ApiRequest,
  id: string,
): Promise<void> {
  const response = await request(`${getApiBase()}/users/members/${id}`, { method: "DELETE" });
  if (!response.ok) throw await parseApiError(response);
}
export async function addMemberToWorkspaceApi(
  request: ApiRequest,
  email: string,
  role: WorkspaceRole,
): Promise<TeamMember> {
  return readJsonOrThrow<TeamMember>(await request(`${getApiBase()}/users/members`, {
    method: "POST",
    body: JSON.stringify({ email, role }),
  }));
}

/** Add-member autocomplete: active organization accounts not in the workspace. */
export async function suggestOrganizationUsersApi(
  request: ApiRequest,
  query: string,
): Promise<OrganizationMember[]> {
  return readJsonOrThrow<OrganizationMember[]>(await request(
    `${getApiBase()}/users/suggest?query=${encodeURIComponent(query)}`));
}

// ─── Organization administration (admin panel, ORG_OWNER only) ─────────────────
// Every call goes through `/api/v1/admin/users/*`: the target needs no
// membership in the actor's active workspace.

export async function getOrganizationMembersApi(
  request: ApiRequest,
): Promise<OrganizationMember[]> {
  return readJsonOrThrow<OrganizationMember[]>(await request(`${getAdminApiBase()}/users/organization`));
}

export async function createOrganizationUserApi(
  request: ApiRequest,
  payload: { email: string; fullName: string; password: string },
): Promise<OrganizationMember> {
  return readJsonOrThrow<OrganizationMember>(await request(`${getAdminApiBase()}/users/organization`, {
    method: "POST",
    body: JSON.stringify(payload),
  }));
}

export async function updateOrgUserStatusApi(
  request: ApiRequest,
  id: string,
  status: UserStatus,
): Promise<OrganizationMember> {
  return readJsonOrThrow<OrganizationMember>(await request(`${getAdminApiBase()}/users/${id}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  }));
}

/** Owner sets an org account password; the member must change it on next login. */
export async function setOrgUserPasswordApi(
  request: ApiRequest,
  id: string,
  password: string,
): Promise<void> {
  const response = await request(`${getAdminApiBase()}/users/${id}/password`, {
    method: "PUT",
    body: JSON.stringify({ password }),
  });
  if (!response.ok) throw await parseApiError(response);
}
