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

import type { NotificationChannelView } from "@/types";
import { getAdminApiBase } from "@/lib/auth-api";
import { parseApiError, readJsonOrThrow } from "@/lib/api-error";
import type { ApiRequest } from "@/lib/documents-api";

// Soft admin boundary: notification channels are ORG_OWNER-only, so every call
// goes through `/api/v1/admin/*` (backend alias, gateway-rate-limited).

export type ChannelPayload = {
  enabled?: boolean;
  fromEmail?: string;
  fromName?: string;
  host?: string;
  port?: number;
  username?: string;
  password?: string;
  auth?: boolean;
  starttls?: boolean;
};

export async function listChannelsApi(
  request: ApiRequest,
): Promise<NotificationChannelView[]> {
  return readJsonOrThrow<NotificationChannelView[]>(await request(`${getAdminApiBase()}/channels`));
}

export async function upsertChannelApi(
  request: ApiRequest,
  type: string,
  payload: ChannelPayload,
): Promise<NotificationChannelView> {
  return readJsonOrThrow<NotificationChannelView>(
    await request(`${getAdminApiBase()}/channels/${type}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    }),
  );
}

export async function setChannelEnabledApi(
  request: ApiRequest,
  id: string,
  enabled: boolean,
): Promise<NotificationChannelView> {
  return readJsonOrThrow<NotificationChannelView>(
    await request(`${getAdminApiBase()}/channels/${id}/enabled`, {
      method: "POST",
      body: JSON.stringify({ enabled }),
    }),
  );
}

export async function deleteChannelApi(request: ApiRequest, id: string): Promise<void> {
  const response = await request(`${getAdminApiBase()}/channels/${id}`, { method: "DELETE" });
  if (!response.ok) throw await parseApiError(response);
}

export async function testChannelApi(request: ApiRequest, email: string): Promise<void> {
  const response = await request(`${getAdminApiBase()}/channels/test`, {
    method: "POST",
    body: JSON.stringify({ email }),
  });
  if (!response.ok) throw await parseApiError(response);
}
