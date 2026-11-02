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

import {
  archiveBizAppRecordApi,
  bizAppLabel,
  createBizAppRecordApi,
  installBizAppApi,
  listBizAppTemplatesApi,
  runBizAppReportApi,
  submitBizAppRecordApi,
  uninstallBizAppApi,
} from "@/lib/bizapp-api";

const seen: Array<{ url: string; init?: RequestInit }> = [];
const request = vi.fn(async (input: string, init?: RequestInit) => {
  seen.push({ url: input, init });
  const path = input;
  if (init?.method === "DELETE") return new Response(null, { status: 204 });
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

  it("resolves localized labels with the active locale", () => {
    expect(bizAppLabel({ name: "CRM Lite", nameVi: "CRM-lite", kind: "crm" }, "vi")).toBe("CRM-lite");
    expect(bizAppLabel({ name: "CRM Lite", nameVi: null, kind: "crm" }, "vi")).toBe("CRM Lite");
    expect(bizAppLabel({ name: "CRM Lite", nameVi: "CRM-lite", kind: "crm" }, "en")).toBe("CRM Lite");
  });
});
