import type { NotificationChannelView } from "@/types";
import { getApiBase } from "@/lib/auth-api";
import { parseApiError, readJsonOrThrow } from "@/lib/api-error";
import type { ApiRequest } from "@/lib/documents-api";

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
  apiKey?: string;
};

export async function listChannelsApi(
  request: ApiRequest,
): Promise<NotificationChannelView[]> {
  return readJsonOrThrow<NotificationChannelView[]>(await request(`${getApiBase()}/channels`));
}

export async function upsertChannelApi(
  request: ApiRequest,
  type: string,
  payload: ChannelPayload,
): Promise<NotificationChannelView> {
  return readJsonOrThrow<NotificationChannelView>(
    await request(`${getApiBase()}/channels/${type}`, {
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
    await request(`${getApiBase()}/channels/${id}/enabled`, {
      method: "POST",
      body: JSON.stringify({ enabled }),
    }),
  );
}

export async function deleteChannelApi(request: ApiRequest, id: string): Promise<void> {
  const response = await request(`${getApiBase()}/channels/${id}`, { method: "DELETE" });
  if (!response.ok) throw await parseApiError(response);
}

export async function testChannelApi(request: ApiRequest, email: string): Promise<void> {
  const response = await request(`${getApiBase()}/channels/test`, {
    method: "POST",
    body: JSON.stringify({ email }),
  });
  if (!response.ok) throw await parseApiError(response);
}
