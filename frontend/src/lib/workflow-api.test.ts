import { beforeAll, describe, expect, it } from "vitest"

import type { ApiRequest } from "@/lib/api-request"
import {
  claimApprovalTaskApi,
  completeApprovalTaskApi,
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
})
