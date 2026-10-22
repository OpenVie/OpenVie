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
