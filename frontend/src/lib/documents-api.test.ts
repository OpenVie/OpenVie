import { beforeAll, describe, expect, it } from "vitest"

import type { ApiRequest } from "@/lib/api-request"
import {
  getDocumentStatusApi,
  isSupportedDocumentName,
  isTerminalDocumentStatus,
  uploadDocumentApi,
} from "@/lib/documents-api"

beforeAll(() => {
  process.env.NEXT_PUBLIC_API_BASE_URL = "http://api.test/api/v1"
})

function jsonResponse(body: unknown, init?: ResponseInit): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
    ...init,
  })
}

describe("uploadDocumentApi", () => {
  it("posts the file and knowledge base to the documents endpoint", async () => {
    const calls: Array<{ endpoint: string; options?: RequestInit }> = []
    const request: ApiRequest = async (endpoint, options) => {
      calls.push({ endpoint, options })
      return jsonResponse({ id: "doc-1", jobId: "job-1", fileName: "guide.pdf", status: "PENDING" })
    }

    const file = new File(["%PDF-1.4"], "guide.pdf", { type: "application/pdf" })
    const result = await uploadDocumentApi(request, file, "kb-1")

    expect(calls).toHaveLength(1)
    expect(calls[0]?.endpoint).toBe("http://api.test/api/v1/documents")
    expect(calls[0]?.options?.method).toBe("POST")

    const body = calls[0]?.options?.body as FormData
    expect(body).toBeInstanceOf(FormData)
    expect((body.get("file") as File).name).toBe("guide.pdf")
    expect(body.get("knowledgeBaseId")).toBe("kb-1")

    expect(result).toMatchObject({ id: "doc-1", status: "PENDING" })
  })

  it("propagates API failures", async () => {
    const request: ApiRequest = async () =>
      jsonResponse({ message: "unsupported file" }, { status: 415 })
    const file = new File(["MZ"], "setup.exe")

    await expect(uploadDocumentApi(request, file, "kb-1")).rejects.toThrow("unsupported file")
  })
})

describe("document status polling", () => {
  it("reads the status of a single document", async () => {
    const endpoints: string[] = []
    const request: ApiRequest = async (endpoint) => {
      endpoints.push(endpoint)
      return jsonResponse({
        id: "doc-9",
        jobId: "job-9",
        fileName: "notes.txt",
        fileType: "TXT",
        fileSizeBytes: 12,
        uploadedAt: "2026-01-01T00:00:00Z",
        knowledgeBaseId: "kb-1",
        status: "PROCESSING",
      })
    }

    const status = await getDocumentStatusApi(request, "doc-9")
    expect(endpoints).toEqual(["http://api.test/api/v1/documents/doc-9"])
    expect(status.status).toBe("PROCESSING")
  })

  it("treats COMPLETED and FAILED as terminal states", () => {
    expect(isTerminalDocumentStatus("COMPLETED")).toBe(true)
    expect(isTerminalDocumentStatus("FAILED")).toBe(true)
    expect(isTerminalDocumentStatus("PENDING")).toBe(false)
    expect(isTerminalDocumentStatus("PROCESSING")).toBe(false)
  })
})

describe("supported document formats", () => {
  it("accepts the promised text-bearing formats", () => {
    for (const name of ["a.pdf", "b.docx", "c.txt", "d.md", "e.html", "f.xlsx", "g.csv"]) {
      expect(isSupportedDocumentName(name)).toBe(true)
    }
  })

  it("rejects unsupported files before upload", () => {
    expect(isSupportedDocumentName("malware.exe")).toBe(false)
    expect(isSupportedDocumentName("photo.png")).toBe(false)
  })
})
