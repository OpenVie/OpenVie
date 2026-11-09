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

import { getApiBase } from "@/lib/auth-api";
import { parseApiError, readJsonOrThrow } from "@/lib/api-error";
import type { ApiRequest } from "@/lib/api-request";
import type { NotificationItem, UnreadNotificationsCount } from "@/types";

export interface NotificationsPage {
  content: NotificationItem[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export async function listNotificationsApi(
  request: ApiRequest,
  page = 0,
  size = 20,
): Promise<NotificationsPage> {
  const res = await request(`${getApiBase()}/notifications?page=${page}&size=${size}`);
  return readJsonOrThrow<NotificationsPage>(res);
}

export async function getUnreadNotificationsCountApi(
  request: ApiRequest,
): Promise<UnreadNotificationsCount> {
  const res = await request(`${getApiBase()}/notifications/unread-count`);
  return readJsonOrThrow<UnreadNotificationsCount>(res);
}

export async function markNotificationAsReadApi(
  request: ApiRequest,
  id: string,
): Promise<void> {
  const res = await request(`${getApiBase()}/notifications/${encodeURIComponent(id)}/read`, {
    method: "POST",
  });
  if (!res.ok) throw await parseApiError(res);
}

export async function markAllNotificationsAsReadApi(
  request: ApiRequest,
): Promise<void> {
  const res = await request(`${getApiBase()}/notifications/read-all`, {
    method: "POST",
  });
  if (!res.ok) throw await parseApiError(res);
}
