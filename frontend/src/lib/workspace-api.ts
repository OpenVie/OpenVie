import type { TenantWorkspace } from "@/types";
import { getApiBase } from "@/lib/auth-api";
import { readJsonOrThrow } from "@/lib/api-error";
import type { ApiRequest } from "@/lib/documents-api";

export async function getTenantWorkspaceApi(
  request: ApiRequest,
): Promise<TenantWorkspace> {
  const res = await request(`${getApiBase()}/tenants/me/workspace`);
  return readJsonOrThrow<TenantWorkspace>(res);
}
