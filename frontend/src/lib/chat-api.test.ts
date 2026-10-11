import { beforeAll, describe, expect, it, vi } from "vitest"

import type { ApiRequest } from "@/lib/api-request"
import {
  ChatMessageTimeoutError,
  submitChatMessageApi,
} from "@/lib/chat-api"
import type { AssistantMessageResponse } from "@/types"

beforeAll(() => {
  process.env.NEXT_PUBLIC_API_BASE_URL = "http://api.test/api/v1"
})

const encoder = new TextEncoder()
const complete: AssistantMessageResponse = {
  role: "assistant",
  content: "Câu trả lời đã được xác minh.",
  citations: [],
}
const completionFrame = `event: complete\ndata: ${JSON.stringify(complete)}\n\n`

function streamResponse(text: string): Response {
  return new Response(new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(encoder.encode(text))
      controller.close()
    },
  }), { headers: { "Content-Type": "text/event-stream; charset=utf-8" } })
}

describe("submitChatMessageApi streaming", () => {
  it("delivers Vietnamese previews across split UTF-8 bytes and CRLF before completion", async () => {
    let streamController!: ReadableStreamDefaultController<Uint8Array>
    const body = new ReadableStream<Uint8Array>({
      start(controller) { streamController = controller },
    })
    const request: ApiRequest = async () => new Response(body, {
      headers: { "Content-Type": "text/event-stream" },
    })
    let previewReceived!: () => void
    const previewReady = new Promise<void>((resolve) => { previewReceived = resolve })
    const previews: string[] = []
    let settled = false
    const result = submitChatMessageApi(request, "session-1", "Hỏi", {
      onContent: (content) => {
        previews.push(content)
        previewReceived()
      },
      onReset: vi.fn(),
    }).finally(() => { settled = true })

    // Every UTF-8 code point and CRLF delimiter can span network chunks.
    const bytes = encoder.encode(': keepalive\r\nevent: content\r\ndata: {\r\ndata: "content": "Tiếng Việt: nghỉ phép"}\r\n\r\n')
    for (const byte of bytes) streamController.enqueue(Uint8Array.of(byte))
    await previewReady
    expect(previews).toEqual(["Tiếng Việt: nghỉ phép"])
    expect(settled).toBe(false)

    streamController.enqueue(encoder.encode(completionFrame))
    await expect(result).resolves.toEqual(complete)
    expect(body.locked).toBe(false)
  })

  it("replaces snapshots, resets a discarded preview and resolves the authoritative completion", async () => {
    const events: string[] = []
    const request: ApiRequest = async () => streamResponse(
      'event: content\ndata: {"content":"Draft"}\n\n' +
      'event: content\ndata: {"content":"Replacement draft"}\n\n' +
      'event: reset\ndata: {}\n\n' +
      'event: content\ndata: {"content":"Revised draft"}\n\n' + completionFrame,
    )

    await expect(submitChatMessageApi(request, "session-1", "Question", {
      onContent: (content) => { events.push(content) },
      onReset: () => { events.push("reset") },
    })).resolves.toEqual(complete)
    expect(events).toEqual(["Draft", "Replacement draft", "reset", "Revised draft"])
  })

  it("surfaces an in-band actionable error instead of resolving a partial answer", async () => {
    const request: ApiRequest = async () => streamResponse(
      'event: content\ndata: {"content":"Uncommitted preview"}\n\n' +
      'event: error\ndata: {"code":"KNOWLEDGE_CHANGED","message":"Knowledge changed. Please try again."}\n\n',
    )

    await expect(submitChatMessageApi(request, "session-1", "Question", {
      onContent: vi.fn(),
      onReset: vi.fn(),
    })).rejects.toMatchObject({ code: "KNOWLEDGE_CHANGED", message: "Knowledge changed. Please try again." })
  })

  it.each([
    ["empty stream", ""],
    ["partial answer", 'event: content\ndata: {"content":"Only a preview"}\n\n'],
    ["unfinished completion delimiter", completionFrame.slice(0, -1)],
    ["malformed JSON", 'event: content\ndata: not-json\n\n'],
    ["blank completed answer", 'event: complete\ndata: {"role":"assistant","content":" ","citations":[]}\n\n'],
  ])("rejects %s without a valid complete event", async (_name, text) => {
    const request: ApiRequest = async () => streamResponse(text)
    await expect(submitChatMessageApi(request, "session-1", "Question", {
      onContent: vi.fn(),
      onReset: vi.fn(),
    })).rejects.toThrow()
  })

  it("cancels and unlocks an open stream while preserving explicit caller cancellation", async () => {
    const caller = new AbortController()
    const cancel = vi.fn()
    let previewReceived!: () => void
    const previewReady = new Promise<void>((resolve) => { previewReceived = resolve })
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: content\ndata: {"content":"Preview"}\n\n'))
      },
      cancel,
    })
    const request: ApiRequest = async () => new Response(body, {
      headers: { "Content-Type": "text/event-stream" },
    })
    const result = submitChatMessageApi(request, "session-1", "Question", {
      onContent: () => { previewReceived() },
      onReset: vi.fn(),
      signal: caller.signal,
    })
    const rejection = expect(result).rejects.toMatchObject({ name: "AbortError" })
    await previewReady
    caller.abort()
    await rejection
    expect(cancel).toHaveBeenCalledOnce()
    expect(body.locked).toBe(false)
  })

  it("keeps a request timeout distinct from caller cancellation", async () => {
    vi.useFakeTimers()
    try {
      let opened!: () => void
      const ready = new Promise<void>((resolve) => { opened = resolve })
      const body = new ReadableStream<Uint8Array>()
      const request: ApiRequest = async () => {
        opened()
        return new Response(body, { headers: { "Content-Type": "text/event-stream" } })
      }
      const result = submitChatMessageApi(request, "session-1", "Question", {
        onContent: vi.fn(),
        onReset: vi.fn(),
      })
      const rejection = expect(result).rejects.toBeInstanceOf(ChatMessageTimeoutError)
      await ready
      await vi.advanceTimersByTimeAsync(90_000)
      await rejection
      expect(body.locked).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })
})
