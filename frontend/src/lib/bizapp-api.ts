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

// BizApp runtime + marketplace client (Tier 3). Mirrors
// com.openvie.api.bizapp.controller — pure functions built on the caller
// provided authenticated fetch, exactly like the other `*.api.ts` modules.

import type { ApiRequest } from "@/lib/api-request";
import { getApiBase } from "@/lib/auth-api";
import { readJsonOrThrow } from "@/lib/api-error";
import type {
  AssistantMessageResponse,
  BizAppBlock,
  BizAppCredentialView,
  BizAppDraftView,
  BizAppGenerateDraftResult,
  BizAppInstallResult,
  BizAppInstalledAppView,
  BizAppManifest,
  BizAppRecordPage,
  BizAppRecordView,
  BizAppTemplateView,
  BizAppValidationResult,
} from "@/types";

// ─── Runtime (installed apps + record store) ────────────────────────────────

export async function listInstalledBizAppsApi(
  request: ApiRequest,
): Promise<BizAppInstalledAppView[]> {
  return readJsonOrThrow<BizAppInstalledAppView[]>(
    await request(`${getApiBase()}/bizapps`),
  );
}

export async function getInstalledBizAppApi(
  request: ApiRequest,
  kind: string,
): Promise<BizAppInstalledAppView> {
  return readJsonOrThrow<BizAppInstalledAppView>(
    await request(`${getApiBase()}/bizapps/${encodeURIComponent(kind)}`),
  );
}

export async function updateBizAppConfigApi(
  request: ApiRequest,
  kind: string,
  config: Record<string, unknown>,
): Promise<BizAppInstalledAppView> {
  return readJsonOrThrow<BizAppInstalledAppView>(
    await request(`${getApiBase()}/bizapps/${encodeURIComponent(kind)}/config`, {
      method: "PATCH",
      body: JSON.stringify({ config }),
    }),
  );
}

export async function listBizAppRecordsApi(
  request: ApiRequest,
  kind: string,
  entity: string,
  options?: { page?: number; size?: number; includeArchived?: boolean },
): Promise<BizAppRecordPage> {
  const page = options?.page ?? 0;
  const size = options?.size ?? 20;
  const archived = options?.includeArchived ? "&includeArchived=true" : "";
  return readJsonOrThrow<BizAppRecordPage>(
    await request(
      `${getApiBase()}/bizapp-data/${encodeURIComponent(kind)}/${encodeURIComponent(entity)}?page=${page}&size=${size}${archived}`,
    ),
  );
}

export async function getBizAppRecordApi(
  request: ApiRequest,
  kind: string,
  entity: string,
  recordId: string,
): Promise<BizAppRecordView> {
  return readJsonOrThrow<BizAppRecordView>(
    await request(
      `${getApiBase()}/bizapp-data/${encodeURIComponent(kind)}/${encodeURIComponent(entity)}/${encodeURIComponent(recordId)}`,
    ),
  );
}

export async function createBizAppRecordApi(
  request: ApiRequest,
  kind: string,
  entity: string,
  payload: Record<string, unknown>,
): Promise<BizAppRecordView> {
  return readJsonOrThrow<BizAppRecordView>(
    await request(`${getApiBase()}/bizapp-data/${encodeURIComponent(kind)}/${encodeURIComponent(entity)}`, {
      method: "POST",
      body: JSON.stringify({ payload }),
    }),
  );
}

export async function updateBizAppRecordApi(
  request: ApiRequest,
  kind: string,
  entity: string,
  recordId: string,
  payload: Record<string, unknown>,
): Promise<BizAppRecordView> {
  return readJsonOrThrow<BizAppRecordView>(
    await request(
      `${getApiBase()}/bizapp-data/${encodeURIComponent(kind)}/${encodeURIComponent(entity)}/${encodeURIComponent(recordId)}`,
      { method: "PATCH", body: JSON.stringify({ payload }) },
    ),
  );
}

export async function archiveBizAppRecordApi(
  request: ApiRequest,
  kind: string,
  entity: string,
  recordId: string,
): Promise<void> {
  await request(`${getApiBase()}/bizapp-data/${encodeURIComponent(kind)}/${encodeURIComponent(entity)}/${encodeURIComponent(recordId)}`, {
    method: "DELETE",
  });
}

export async function submitBizAppRecordApi(
  request: ApiRequest,
  kind: string,
  entity: string,
  recordId: string,
): Promise<BizAppRecordView> {
  return readJsonOrThrow<BizAppRecordView>(
    await request(
      `${getApiBase()}/bizapp-data/${encodeURIComponent(kind)}/${encodeURIComponent(entity)}/${encodeURIComponent(recordId)}/submit`,
      { method: "POST", body: JSON.stringify({}) },
    ),
  );
}

/** Report views are workspace-admin material (the API enforces 403). */
export async function runBizAppReportApi(
  request: ApiRequest,
  kind: string,
  viewName: string,
  params?: Record<string, unknown>,
): Promise<Array<Record<string, unknown>>> {
  return readJsonOrThrow<Array<Record<string, unknown>>>(
    await request(
      `${getApiBase()}/bizapps/${encodeURIComponent(kind)}/reports/${encodeURIComponent(viewName)}`,
      { method: "POST", body: JSON.stringify({ params: params ?? {} }) },
    ),
  );
}

// ─── Marketplace (browse + install) ─────────────────────────────────────────

export async function listBizAppTemplatesApi(
  request: ApiRequest,
): Promise<BizAppTemplateView[]> {
  return readJsonOrThrow<BizAppTemplateView[]>(
    await request(`${getApiBase()}/marketplace/bizapps`),
  );
}

export async function getBizAppTemplateApi(
  request: ApiRequest,
  templateRef: string,
): Promise<Record<string, unknown>> {
  return readJsonOrThrow<Record<string, unknown>>(
    await request(`${getApiBase()}/marketplace/bizapps/${encodeURIComponent(templateRef)}`),
  );
}

/** Saves an arbitrary (validated backend-side) manifest as an org template. */
export async function saveBizAppTemplateApi(
  request: ApiRequest,
  body: { name: string; summary?: string; manifest: Record<string, unknown> },
): Promise<BizAppTemplateView> {
  return readJsonOrThrow<BizAppTemplateView>(
    await request(`${getApiBase()}/marketplace/bizapps`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  );
}

export async function deleteBizAppTemplateApi(
  request: ApiRequest,
  templateId: string,
): Promise<void> {
  await request(`${getApiBase()}/marketplace/bizapps/${encodeURIComponent(templateId)}`, {
    method: "DELETE",
  });
}

export async function installBizAppApi(
  request: ApiRequest,
  target: string | { templateRef?: string; manifest?: BizAppManifest; draftId?: string },
): Promise<BizAppInstallResult> {
  const body = typeof target === "string" ? { templateRef: target } : target;
  return readJsonOrThrow<BizAppInstallResult>(
    await request(`${getApiBase()}/apps/install`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  );
}

// ─── Drafts (AI drafting + composer, owner) ─────────────────────────────────

export async function listBizAppDraftsApi(
  request: ApiRequest,
): Promise<BizAppDraftView[]> {
  return readJsonOrThrow<BizAppDraftView[]>(
    await request(`${getApiBase()}/bizapps/drafts`),
  );
}

export async function getBizAppDraftApi(
  request: ApiRequest,
  draftId: string,
): Promise<BizAppDraftView> {
  return readJsonOrThrow<BizAppDraftView>(
    await request(`${getApiBase()}/bizapps/drafts/${encodeURIComponent(draftId)}`),
  );
}

/**
 * Saves a draft; `"new"` as the id creates one (backend `PUT /drafts/new`).
 * The returned view carries the stored manifest and a fresh validation.
 */
export async function saveBizAppDraftApi(
  request: ApiRequest,
  draftId: string,
  body: { id?: string; title?: string; source?: string; manifest: BizAppManifest; meta?: Record<string, unknown> },
): Promise<BizAppDraftView> {
  return readJsonOrThrow<BizAppDraftView>(
    await request(`${getApiBase()}/bizapps/drafts/${encodeURIComponent(draftId)}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),
  );
}

export async function deleteBizAppDraftApi(
  request: ApiRequest,
  draftId: string,
): Promise<void> {
  await request(`${getApiBase()}/bizapps/drafts/${encodeURIComponent(draftId)}`, {
    method: "DELETE",
  });
}

/**
 * Drafts a manifest from a natural-language prompt. The result is a draft
 * only — it never installs until the owner reviews and installs it.
 */
export async function generateBizAppDraftApi(
  request: ApiRequest,
  body: { prompt: string; locale?: string; baseTemplateRef?: string },
): Promise<BizAppGenerateDraftResult> {
  return readJsonOrThrow<BizAppGenerateDraftResult>(
    await request(`${getApiBase()}/bizapps/drafts/generate`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  );
}

/**
 * Validates a manifest against the bizapp.v1 schema rules without saving.
 */
export async function validateBizAppManifestApi(
  request: ApiRequest,
  manifest: BizAppManifest,
): Promise<BizAppValidationResult> {
  return readJsonOrThrow<BizAppValidationResult>(
    await request(`${getApiBase()}/bizapps/validate`, {
      method: "POST",
      body: JSON.stringify({ manifest }),
    }),
  );
}

// ─── Template exchange (export / import / remix, owner) ─────────────────────

/** Downloads the `openvie.template.v1` envelope (manifest + provenance meta). */
export async function exportBizAppTemplateApi(
  request: ApiRequest,
  templateRef: string,
): Promise<Record<string, unknown>> {
  return readJsonOrThrow<Record<string, unknown>>(
    await request(`${getApiBase()}/marketplace/bizapps/${encodeURIComponent(templateRef)}/export`),
  );
}

/** Imports an envelope (pasted JSON or URL + SHA-256) as a draft. */
export async function importBizAppTemplateApi(
  request: ApiRequest,
  body: { content?: string; url?: string; sha256?: string },
): Promise<BizAppDraftView> {
  return readJsonOrThrow<BizAppDraftView>(
    await request(`${getApiBase()}/marketplace/bizapps/import`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  );
}

/** Remixes a template: copy to draft, retain upstream attribution, append a change entry. */
export async function remixBizAppTemplateApi(
  request: ApiRequest,
  templateRef: string,
  summary?: string,
): Promise<BizAppDraftView> {
  return readJsonOrThrow<BizAppDraftView>(
    await request(`${getApiBase()}/marketplace/bizapps/${encodeURIComponent(templateRef)}/remix`, {
      method: "POST",
      body: JSON.stringify(summary ? { summary } : {}),
    }),
  );
}

export async function uninstallBizAppApi(
  request: ApiRequest,
  kind: string,
): Promise<void> {
  await request(`${getApiBase()}/apps/${encodeURIComponent(kind)}`, { method: "DELETE" });
}

// ─── Credential vault (owner) ───────────────────────────────────────────────

export async function listBizAppCredentialsApi(
  request: ApiRequest,
): Promise<BizAppCredentialView[]> {
  return readJsonOrThrow<BizAppCredentialView[]>(
    await request(`${getApiBase()}/bizapp-credentials`),
  );
}

export async function createBizAppCredentialApi(
  request: ApiRequest,
  body: { name: string; format: "api_key" | "bearer" | "basic"; secret: string; user?: string },
): Promise<BizAppCredentialView> {
  return readJsonOrThrow<BizAppCredentialView>(
    await request(`${getApiBase()}/bizapp-credentials`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  );
}

export async function deleteBizAppCredentialApi(
  request: ApiRequest,
  credentialId: string,
): Promise<void> {
  await request(`${getApiBase()}/bizapp-credentials/${encodeURIComponent(credentialId)}`, {
    method: "DELETE",
  });
}

export async function executeChatActionApi(
  request: ApiRequest,
  messageId: string,
  actionId: string,
): Promise<AssistantMessageResponse> {
  return readJsonOrThrow<AssistantMessageResponse>(
    await request(`${getApiBase()}/chat/actions/execute`, {
      method: "POST",
      body: JSON.stringify({ messageId, actionId }),
    }),
  );
}

export async function createDraftFromChatApi(
  request: ApiRequest,
  sessionId: string,
  hint?: string,
): Promise<BizAppGenerateDraftResult> {
  return readJsonOrThrow<BizAppGenerateDraftResult>(
    await request(`${getApiBase()}/bizapps/drafts/from-chat`, {
      method: "POST",
      body: JSON.stringify({ sessionId, hint }),
    }),
  );
}

// ─── Manifest helpers ───────────────────────────────────────────────────────

/** Localized label lookup honoring the active locale (fallback: the other one). */
export function bizAppLabel(manifest: Pick<BizAppTemplateView, "name" | "nameVi" | "kind">, locale: string): string {
  return locale === "vi" && manifest.nameVi ? manifest.nameVi : manifest.name;
}

/** True when the manifest explicitly grants MEMBER write (registry/forms). */
export function memberCanWrite(manifest: BizAppManifest): boolean {
  return Boolean(manifest.rbac?.write?.includes("MEMBER"));
}

/** The app's approval block, when it declares one. */
export function bizAppApprovalBlock(manifest: BizAppManifest): BizAppBlock | undefined {
  return manifest.blocks?.find((block) => block.type === "approval_workitem");
}
