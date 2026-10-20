import { beforeAll, describe, expect, it } from "vitest"

import type { ApiRequest } from "@/lib/api-request"
import {
  claimApprovalTaskApi,
  completeApprovalTaskApi,
  getProcessLogApi,
  listApprovalProcessesPagedApi,
  listApprovalTasksApi,
  startApprovalApi,
} from "@/lib/workflow-api"

beforeAll(() => {
  process.env.NEXT_PUBLIC_API_BASE_URL = "http://api.test/api/v1"
})

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  })
}

describe("workflow API", () => {
  it("reads the paged approval window and the total count header", async () => {
    const endpoints: string[] = []
    const request: ApiRequest = async (endpoint) => {
      endpoints.push(endpoint)
      return new Response(JSON.stringify([{ processInstanceId: "process-1" }]), {
        status: 200,
        headers: { "Content-Type": "application/json", "X-Total-Count": "41" },
      })
    }

    const result = await listApprovalProcessesPagedApi(request, { includeFinished: true, page: 2, size: 10 })

    expect(endpoints[0]).toContain("/workflow/processes?")
    const query = new URL(endpoints[0]).search
    expect(query).toContain("includeFinished=true")
    expect(query).toContain("page=2")
    expect(query).toContain("size=10")
    expect(result.total).toBe(41)
    expect(result.items).toEqual([{ processInstanceId: "process-1" }])
  })

  it("submits a document with the selected publish target", async () => {
    const calls: Array<{ endpoint: string; options?: RequestInit }> = []
    const request: ApiRequest = async (endpoint, options) => {
      calls.push({ endpoint, options })
      return jsonResponse({ processInstanceId: "process-1" })
    }

    await startApprovalApi(request, "document-1", "shared")

    expect(calls[0]?.endpoint).toBe("http://api.test/api/v1/workflow/processes")
    expect(calls[0]?.options?.method).toBe("POST")
    expect(JSON.parse(String(calls[0]?.options?.body))).toEqual({
      documentId: "document-1",
      publishTarget: "shared",
    })
  })

  it("lists, claims, and completes an approval task", async () => {
    const endpoints: string[] = []
    const request: ApiRequest = async (endpoint, options) => {
      endpoints.push(`${options?.method ?? "GET"} ${endpoint}`)
      return jsonResponse(options?.method === "POST" ? { taskId: "task-1" } : { assignedToMe: [], groupTasks: [] })
    }

    await listApprovalTasksApi(request)
    await claimApprovalTaskApi(request, "task-1")
    await completeApprovalTaskApi(request, "task-1", "REJECT", "Needs a signature")

    expect(endpoints).toEqual([
      "GET http://api.test/api/v1/workflow/tasks",
      "POST http://api.test/api/v1/workflow/tasks/task-1/claim",
      "POST http://api.test/api/v1/workflow/tasks/task-1/complete",
    ])
  })

  it("fetches the audited log of one process", async () => {
    const calls: Array<{ endpoint: string; options?: RequestInit }> = []
    const request: ApiRequest = async (endpoint, options) => {
      calls.push({ endpoint, options })
      return jsonResponse({
        process: { processInstanceId: "process-1", documentStatus: "IN_REVIEW" },
        entries: [
          { occurredAt: "2026-01-01T00:00:00Z", actorId: "user-1", actorName: "Nguyen Van A", action: "WORKFLOW_STARTED", metadata: {} },
        ],
      })
    }

    const log = await getProcessLogApi(request, "process-1")

    expect(calls[0]?.endpoint).toBe("http://api.test/api/v1/workflow/processes/process-1/log")
    expect(log.entries[0]?.action).toBe("WORKFLOW_STARTED")
    expect(log.entries[0]?.actorName).toBe("Nguyen Van A")
  })
})
