import { beforeEach, describe, expect, it } from "vitest"

import {
  consumePendingDocumentScope,
  requestChatWithDocumentScope,
} from "@/lib/chat-events"

describe("pending document scope hand-off", () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it("round-trips a scope request through storage exactly once", () => {
    requestChatWithDocumentScope("doc-1", "doc-2")

    expect(consumePendingDocumentScope()).toEqual(["doc-1", "doc-2"])
    expect(consumePendingDocumentScope()).toBeNull()
  })

  it("treats absent, malformed or empty payloads as no scope", () => {
    expect(consumePendingDocumentScope()).toBeNull()

    window.sessionStorage.setItem("openvie.chat.pending-document-scope", "{not-json")
    expect(consumePendingDocumentScope()).toBeNull()

    window.sessionStorage.setItem("openvie.chat.pending-document-scope", `"just-a-string"`)
    expect(consumePendingDocumentScope()).toBeNull()

    window.sessionStorage.setItem("openvie.chat.pending-document-scope", "[]")
    expect(consumePendingDocumentScope()).toBeNull()
  })

  it("drops non-string entries from the stored scope", () => {
    window.sessionStorage.setItem(
      "openvie.chat.pending-document-scope",
      JSON.stringify(["doc-1", 42, null, "doc-3"]),
    )
    expect(consumePendingDocumentScope()).toEqual(["doc-1", "doc-3"])
  })
})
