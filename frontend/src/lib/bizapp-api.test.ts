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

import { beforeEach, describe, expect, it, vi } from "vitest";

import type { BizAppManifest } from "@/types";
import {
  archiveBizAppRecordApi,
  bizAppLabel,
  canSubmitRecord,
  createBizAppRecordApi,
  deleteBizAppDraftApi,
  exportBizAppTemplateApi,
  generateBizAppDraftApi,
  importBizAppTemplateApi,
  installBizAppApi,
  listBizAppDraftsApi,
  listBizAppTemplatesApi,
  manifestAllows,
  remixBizAppTemplateApi,
  runBizAppReportApi,
  saveBizAppDraftApi,
  submitBizAppRecordApi,
  uninstallBizAppApi,
  upgradeBizAppApi,
  validateBizAppManifestApi,
} from "@/lib/bizapp-api";

const draft = {
  id: "d1",
  title: "Leads",
  source: "canvas",
  manifest: { schema: "bizapp.v1", kind: "leads" },
  validation: { ok: true, errors: [] },
  meta: { generator: "human" },
  createdAt: "2026-01-01T00:00:00",
  updatedAt: "2026-01-01T00:00:01",
};

const seen: Array<{ url: string; init?: RequestInit }> = [];
const request = vi.fn(async (input: string, init?: RequestInit) => {
  seen.push({ url: input, init });
  const path = input;
  if (init?.method === "DELETE") return new Response(null, { status: 204 });
  if (path.endsWith("/bizapps/drafts/generate")) {
    return Response.json(
      { draftId: "d1", manifest: {}, validation: { ok: true, errors: [] }, meta: { generator: "ai" } },
      { status: 201 },
    );
  }
  if (path.endsWith("/bizapps/drafts/new")) {
    return Response.json(draft, { status: 200 });
  }
  if (path.endsWith("/bizapps/drafts")) {
    return Response.json([draft], { status: 200 });
  }
  if (path.endsWith("/bizapps/validate")) {
    return Response.json({ ok: true, errors: [] }, { status: 200 });
  }
  if (path.endsWith("/marketplace/bizapps/import")) {
    return Response.json(draft, { status: 201 });
  }
  if (path.endsWith("/remix")) {
    return Response.json(draft, { status: 201 });
  }
  if (path.endsWith("/export")) {
    return Response.json(
      { envelope: "openvie.template.v1", meta: { license: "Apache-2.0" }, manifest: { kind: "crm" } },
      { status: 200 },
    );
  }
  if (path.endsWith("/marketplace/bizapps")) {
    return Response.json(
      [{ id: null, source: "platform", kind: "crm", name: "CRM Lite", nameVi: "CRM-lite", version: 1 }],
      { status: 200 },
    );
  }
  if (path.endsWith("/reports/pipeline")) {
    return Response.json([{ name: "Acme", stage: "NEW" }], { status: 200 });
  }
  if (path.endsWith("/submit")) {
    return Response.json(
      { id: "r1", appKind: "crm", entity: "leads", payload: {}, runtime: { approvalStatus: "PENDING" }, status: "ACTIVE" },
      { status: 200 },
    );
  }
  if (init?.method === "POST") {
    if (path.endsWith("/apps/crm/upgrade")) {
      return Response.json({ installationId: "i1", kind: "crm", version: 2, processKey: "bizapp-crm" }, { status: 200 });
    }
    if (path.endsWith("/apps/install")) {
      return Response.json({ installationId: "i1", kind: "crm", version: 1, processKey: null }, { status: 201 });
    }
    return Response.json(
      {
        id: "r1", appKind: "crm", entity: "leads", payload: {}, runtime: null,
        status: "ACTIVE", createdBy: "u1", createdAt: "2026-01-01T00:00:00", updatedAt: "2026-01-01T00:00:00",
      },
      { status: 201 },
    );
  }
  return Response.json(
    {
      content: [{ id: "r1", appKind: "crm", entity: "leads", payload: {}, status: "ACTIVE" }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    },
    { status: 200 },
  );
}) as unknown as typeof fetch;

describe("bizapp api client", () => {
  beforeEach(() => {
    seen.length = 0;
    vi.clearAllMocks();
  });

  it("lists marketplace templates", async () => {
    const templates = await listBizAppTemplatesApi(request);
    expect(templates[0]).toMatchObject({ kind: "crm", source: "platform" });
  });

  it("creates records posting the payload envelope", async () => {
    const record = await createBizAppRecordApi(request, "crm", "leads", { name: "Acme" });
    expect(seen[0].url).toContain("/bizapp-data/crm/leads");
    expect(JSON.parse(seen[0].init?.body as string)).toEqual({ payload: { name: "Acme" } });
    expect(record.id).toBe("r1");
  });

  it("archives records with DELETE", async () => {
    await archiveBizAppRecordApi(request, "crm", "leads", "r1");
    expect(seen[0].url).toContain("/bizapp-data/crm/leads/r1");
    expect(seen[0].init?.method).toBe("DELETE");
  });

  it("submits records for approval", async () => {
    const record = await submitBizAppRecordApi(request, "crm", "leads", "r1");
    expect(seen[0].url).toContain("/submit");
    expect(record.runtime).toMatchObject({ approvalStatus: "PENDING" });
  });

  it("runs report views with a wrapped params body", async () => {
    const rows = await runBizAppReportApi(request, "crm", "pipeline", { stage: "NEW" });
    expect(rows[0]).toMatchObject({ name: "Acme" });
    expect(JSON.parse(seen[0].init?.body as string)).toEqual({ params: { stage: "NEW" } });
  });

  it("installs from a template ref and uninstall with DELETE", async () => {
    const result = await installBizAppApi(request, "platform:crm");
    expect(JSON.parse(seen[0].init?.body as string)).toEqual({ templateRef: "platform:crm" });
    expect(result).toMatchObject({ kind: "crm" });

    await uninstallBizAppApi(request, "crm");
    expect(seen[1].init?.method).toBe("DELETE");
    expect(seen[1].url).toContain("/apps/crm");
  });

  it("upgrades an installed app in place with the manifest body", async () => {
    const manifest = {
      schema: "bizapp.v1",
      kind: "crm",
      name: { en: "CRM", vi: "CRM" },
      version: 2,
      entities: [],
    } as unknown as BizAppManifest;

    const result = await upgradeBizAppApi(request, "crm", manifest);

    expect(seen[0].url).toContain("/apps/crm/upgrade");
    expect(seen[0].init?.method).toBe("POST");
    expect(JSON.parse(seen[0].init?.body as string)).toEqual({ manifest });
    expect(result).toMatchObject({ kind: "crm", version: 2 });
  });

  it("resolves manifest capabilities the same way the server does", () => {
    const manifest = {
      rbac: { write: ["ORG_OWNER", "WORKSPACE_ADMIN"], submit: ["MEMBER"] },
    };

    expect(manifestAllows(manifest, "write", "ORG_OWNER", [])).toBe(true);
    expect(manifestAllows(manifest, "write", "WORKSPACE_ADMIN", [])).toBe(true);
    expect(manifestAllows(manifest, "write", "MEMBER", [])).toBe(false);
    expect(manifestAllows({ rbac: undefined }, "read", "MEMBER", ["MEMBER"])).toBe(true);
    expect(manifestAllows({ rbac: { read: ["ORG_OWNER"] } }, "read", "MEMBER", ["MEMBER"])).toBe(false);
    expect(manifestAllows(manifest, "write", null, ["ORG_OWNER"])).toBe(false);
  });

  it("lets submit fall back to write and honors explicit submit lists", () => {
    expect(canSubmitRecord({ rbac: { write: ["MEMBER"] } }, "MEMBER")).toBe(true);
    expect(canSubmitRecord({ rbac: { submit: ["MEMBER"], write: ["ORG_OWNER"] } }, "MEMBER")).toBe(true);
    expect(canSubmitRecord({ rbac: { submit: ["MEMBER"] } }, "ORG_OWNER")).toBe(true);
    expect(canSubmitRecord({ rbac: { write: ["ORG_OWNER"] } }, "MEMBER")).toBe(false);
    expect(canSubmitRecord({ rbac: undefined }, "WORKSPACE_ADMIN")).toBe(true);
  });

  it("lists, saves and deletes drafts through the drafts routes", async () => {
    const drafts = await listBizAppDraftsApi(request);
    expect(drafts[0]).toMatchObject({ id: "d1", source: "canvas" });
    expect(seen[0].url).toContain("/bizapps/drafts");

    const saved = await saveBizAppDraftApi(request, "new", {
      title: "Leads",
      source: "canvas",
      manifest: {
        schema: "bizapp.v1",
        kind: "leads",
        name: { en: "Leads", vi: "Khách hàng" },
        version: 1,
        entities: [],
      },
    });
    expect(seen[1].url).toContain("/bizapps/drafts/new");
    expect(seen[1].init?.method).toBe("PUT");
    expect(saved.id).toBe("d1");

    await deleteBizAppDraftApi(request, "d1");
    expect(seen[2].init?.method).toBe("DELETE");
    expect(seen[2].url).toContain("/bizapps/drafts/d1");
  });

  it("generates an AI draft and never installs it", async () => {
    const result = await generateBizAppDraftApi(request, { prompt: "inventory app" });
    expect(seen[0].url).toContain("/bizapps/drafts/generate");
    expect(JSON.parse(seen[0].init?.body as string)).toEqual({ prompt: "inventory app" });
    expect(result.draftId).toBe("d1");
  });

  it("exports, imports and remixes template envelopes", async () => {
    const envelope = await exportBizAppTemplateApi(request, "platform:crm");
    expect(envelope).toMatchObject({ envelope: "openvie.template.v1" });

    const imported = await importBizAppTemplateApi(request, { content: "{}" });
    expect(seen[1].url).toContain("/marketplace/bizapps/import");
    expect(imported.id).toBe("d1");

    const remixed = await remixBizAppTemplateApi(request, "platform:crm", "add notes field");
    expect(seen[2].url).toContain("/marketplace/bizapps/platform%3Acrm/remix");
    expect(JSON.parse(seen[2].init?.body as string)).toEqual({ summary: "add notes field" });
    expect(remixed.id).toBe("d1");
  });

  it("validates manifests through validateBizAppManifestApi", async () => {
    const result = await validateBizAppManifestApi(request, {
      schema: "bizapp.v1",
      kind: "test",
      name: { en: "Test", vi: "Thu nghiem" },
      entities: [],
    });
    expect(result.ok).toBe(true);
    expect(result.errors).toEqual([]);
    expect(seen[seen.length - 1].url).toContain("/bizapps/validate");
  });

  it("resolves localized labels with the active locale", () => {
    expect(bizAppLabel({ name: "CRM Lite", nameVi: "CRM-lite", kind: "crm" }, "vi")).toBe("CRM-lite");
    expect(bizAppLabel({ name: "CRM Lite", nameVi: null, kind: "crm" }, "vi")).toBe("CRM Lite");
    expect(bizAppLabel({ name: "CRM Lite", nameVi: "CRM-lite", kind: "crm" }, "en")).toBe("CRM Lite");
  });
});
