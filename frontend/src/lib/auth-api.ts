import type {
  AuthResponse,
  InvitationValidation,
  LoginResponse,
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

export async function loginApi(payload: {
  email: string;
  password: string;
  rememberMe: boolean;
}): Promise<LoginResponse> {
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
  return body as LoginResponse;
}

export async function verifyLogin2FAApi(token: string): Promise<AuthResponse> {
  const res = await fetch(`${getApiBase()}/auth/verify-login-2fa`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ token }),
  });
  const body = await parseJsonSafe(res);
  if (!res.ok) {
    throw authApiError(body);
  }
  return body as AuthResponse;
}

export async function resendLogin2FAApi(
  email: string,
): Promise<{ message: string; canRetryAfterSeconds?: number }> {
  const res = await fetch(`${getApiBase()}/auth/resend-login-2fa`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ email }),
  });
  const body = await parseJsonSafe(res);
  if (!res.ok) {
    throw authApiError(body);
  }
  return body as { message: string; canRetryAfterSeconds?: number };
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
