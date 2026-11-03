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

import { getAdminApiBase } from "@/lib/auth-api";
import { parseApiError, readJsonOrThrow } from "@/lib/api-error";
import type { ApiRequest } from "@/lib/documents-api";
import type { ApiKeyView, CreatedApiKey, WorkspaceRole } from "@/types";

// ─── Machine API keys (admin panel, ORG_OWNER only) ─────────────────────────
// Every call goes through `/api/v1/admin/api-keys/*`. The key authenticates as
// a real service member inside the workspace bound at creation; the secret is
// returned exactly once and `keyView` on later calls is always masked.

export async function listApiKeysApi(
  request: ApiRequest,
): Promise<ApiKeyView[]> {
  return readJsonOrThrow<ApiKeyView[]>(await request(`${getAdminApiBase()}/api-keys`));
}

export async function createApiKeyApi(
  request: ApiRequest,
  payload: { name: string; workspaceId: string; role: WorkspaceRole; expiresAt?: string },
): Promise<CreatedApiKey> {
  return readJsonOrThrow<CreatedApiKey>(await request(`${getAdminApiBase()}/api-keys`, {
    method: "POST",
    body: JSON.stringify({
      name: payload.name,
      workspaceId: payload.workspaceId,
      role: payload.role,
      ...(payload.expiresAt ? { expiresAt: payload.expiresAt } : {}),
    }),
  }));
}

/** Revocation is immediate: the key stops authenticating and its service account is suspended. */
export async function revokeApiKeyApi(
  request: ApiRequest,
  id: string,
): Promise<void> {
  const response = await request(`${getAdminApiBase()}/api-keys/${id}`, { method: "DELETE" });
  if (!response.ok) throw await parseApiError(response);
}
