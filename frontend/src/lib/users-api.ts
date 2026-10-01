import { getApiBase } from "@/lib/auth-api";
import { parseApiError, readJsonOrThrow } from "@/lib/api-error";
import type { ApiRequest } from "@/lib/documents-api";
import type { TeamDirectory, TeamInvitation, TeamMember, UserRole, UserStatus } from "@/types";

export async function getTeamDirectory(request: ApiRequest): Promise<TeamDirectory> {
  return readJsonOrThrow<TeamDirectory>(await request(`${getApiBase()}/users/directory`));
}

export async function inviteTeamMember(request: ApiRequest, email: string, role: UserRole): Promise<TeamInvitation> {
  return readJsonOrThrow<TeamInvitation>(await request(`${getApiBase()}/users/invitations`, {
    method: "POST", body: JSON.stringify({ email, role }),
  }));
}

export async function resendTeamInvitation(request: ApiRequest, id: string): Promise<TeamInvitation> {
  return readJsonOrThrow<TeamInvitation>(await request(`${getApiBase()}/users/invitations/${id}/resend`, { method: "POST" }));
}

export async function cancelTeamInvitation(request: ApiRequest, id: string): Promise<void> {
  const response = await request(`${getApiBase()}/users/invitations/${id}`, { method: "DELETE" });
  if (!response.ok) throw await parseApiError(response);
}

export async function updateTeamMemberRole(request: ApiRequest, id: string, role: UserRole): Promise<TeamMember> {
  return readJsonOrThrow<TeamMember>(await request(`${getApiBase()}/users/${id}/role`, {
    method: "PATCH", body: JSON.stringify({ role }),
  }));
}

export async function updateTeamMemberStatus(request: ApiRequest, id: string, status: UserStatus): Promise<TeamMember> {
  return readJsonOrThrow<TeamMember>(await request(`${getApiBase()}/users/${id}/status`, {
    method: "PATCH", body: JSON.stringify({ status }),
  }));
}
