import type {
  AuthResponse,
  InvitationValidation,
  RegistrationStatus,
  WorkspaceSummary,
} from "@/types";

export function getApiBase(): string {
  const canonical = process.env.NEXT_PUBLIC_API_BASE_URL;
  if (canonical) {
    return canonical.replace(/\/$/, "");
  }

  const legacy = process.env.NEXT_PUBLIC_API_URL;
  if (legacy) {
    return `${legacy.replace(/\/$/, "")}/api`;
  }

  throw new Error("NEXT_PUBLIC_API_BASE_URL is not set");
}

type ApiErrorBody = {
  message?: string | string[];
};

class AuthApiFallbackError extends Error {
  constructor() {
    super("AUTH_API_FALLBACK");
    this.name = "AuthApiFallbackError";
  }
}

function parseErrorMessage(body: unknown): string | null {
  if (!body || typeof body !== "object") return null;
  const msg = (body as ApiErrorBody).message;
  if (typeof msg === "string") return msg;
  if (Array.isArray(msg)) return msg.join(" ");
  return null;
}

function authApiError(body: unknown): Error {
  const message = parseErrorMessage(body);
  return message ? new Error(message) : new AuthApiFallbackError();
}

export function authApiErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof AuthApiFallbackError) return fallback;
  return error instanceof Error ? error.message : fallback;
}

async function parseJsonSafe(res: Response): Promise<unknown> {
  try {
    return await res.json();
  } catch {
    return null;
  }
}

let refreshInFlight: Promise<AuthResponse> | null = null;

export async function refreshApi(): Promise<AuthResponse> {
  if (refreshInFlight) return refreshInFlight;

  refreshInFlight = (async () => {
    const res = await fetch(`${getApiBase()}/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
    });
    const body = await parseJsonSafe(res);
    if (!res.ok) {
      throw authApiError(body);
    }
    return body as AuthResponse;
  })().finally(() => {
    refreshInFlight = null;
  });

  return refreshInFlight;
}

/** Password-only login; the response is a complete session or an error. */
export async function loginApi(payload: {
  email: string;
  password: string;
  rememberMe: boolean;
}): Promise<AuthResponse> {
  const res = await fetch(`${getApiBase()}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(payload),
  });
  const body = await parseJsonSafe(res);
  if (!res.ok) {
    throw authApiError(body);
  }
  return body as AuthResponse;
}

export async function logoutApi(): Promise<void> {
  const res = await fetch(`${getApiBase()}/auth/logout`, {
    method: "POST",
    credentials: "include",
  });
  if (!res.ok && res.status !== 204) {
    const body = await parseJsonSafe(res);
    throw authApiError(body);
  }
}

/** Workspaces the signed-in user belongs to. */
export async function listWorkspacesApi(
  accessToken: string,
): Promise<WorkspaceSummary[]> {
  const res = await fetch(`${getApiBase()}/auth/workspaces`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    credentials: "include",
  });
  const body = await parseJsonSafe(res);
  if (!res.ok) throw authApiError(body);
  return body as WorkspaceSummary[];
}

/** Re-issues the session scoped to another workspace the caller belongs to. */
export async function switchWorkspaceApi(workspaceId: string): Promise<AuthResponse> {
  const res = await fetch(`${getApiBase()}/auth/workspaces/switch`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ workspaceId }),
  });
  const body = await parseJsonSafe(res);
  if (!res.ok) throw authApiError(body);
  return body as AuthResponse;
}

export async function changePasswordApi(
  accessToken: string,
  payload: { currentPassword: string; newPassword: string },
): Promise<void> {
  const res = await fetch(`${getApiBase()}/auth/change-password`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${accessToken}`,
    },
    credentials: "include",
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    const body = await parseJsonSafe(res);
    throw authApiError(body);
  }
}

/** Self-service signup while the organization allows it. */
export async function registerApi(payload: {
  email: string;
  fullName: string;
  password: string;
}): Promise<AuthResponse> {
  const res = await fetch(`${getApiBase()}/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  const body = await parseJsonSafe(res);
  if (!res.ok) throw authApiError(body);
  return body as AuthResponse;
}

export async function registrationStatusApi(): Promise<RegistrationStatus> {
  const res = await fetch(`${getApiBase()}/auth/registration-status`, {
    credentials: "include",
  });
  const body = await parseJsonSafe(res);
  if (!res.ok) throw authApiError(body);
  return body as RegistrationStatus;
}

/** One-time web setup: claims the installation and creates the owner. */
export async function setupApi(payload: {
  organizationName: string;
  fullName: string;
  email: string;
  password: string;
  allowSelfRegistration: boolean;
}): Promise<void> {
  const res = await fetch(`${getApiBase()}/setup`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    const body = await parseJsonSafe(res);
    throw authApiError(body);
  }
}

export async function validateInvitationApi(token: string): Promise<InvitationValidation> {
  const params = new URLSearchParams({ token });
  const res = await fetch(`${getApiBase()}/auth/invitations/validate?${params.toString()}`, {
    credentials: "include",
  });
  const body = await parseJsonSafe(res);
  if (!res.ok) throw authApiError(body);
  return body as InvitationValidation;
}

export async function acceptInvitationApi(payload: {
  token: string;
  fullName: string;
  password: string;
}): Promise<AuthResponse> {
  const res = await fetch(`${getApiBase()}/auth/invitations/accept`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(payload),
  });
  const body = await parseJsonSafe(res);
  if (!res.ok) throw authApiError(body);
  return body as AuthResponse;
}
