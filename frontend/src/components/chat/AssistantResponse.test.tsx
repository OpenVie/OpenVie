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

import { render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { loadCitationImageApi } from "@/lib/documents-api"
import { AssistantResponse } from "@/components/chat/AssistantResponse"
import type { ChatCitation } from "@/types"

vi.mock("next-intl", () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    key === "openSourceIn" ? `Open ${String(params?.source)}` : key,
}))

vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children, ...rest }: React.ComponentProps<"a">) => (
    <a href={typeof href === "string" ? href : undefined} {...rest}>
      {children}
    </a>
  ),
}))

vi.mock("@/hooks/useApiClient", () => ({
  useApiClient: () => ({ request: vi.fn() }),
}))

vi.mock("@/lib/documents-api", async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  loadCitationImageApi: vi.fn(async (_request: unknown, documentId: string, mediaId: string) => {
    if (mediaId === "fail") throw new Error("not found")
    expect(documentId).toBe("doc-42")
    return new Blob([new Uint8Array([1, 2, 3])], { type: "image/png" })
  }),
}))

const citation: ChatCitation = {
  id: "S1",
  document_id: "doc-42",
  source_name: "handbook.pdf",
  page_number: 3,
  chunk_index: 7,
  score: 0.91,
  snippet: " Vacation requests need approval.",
  unit_id: "unit-9",
}

beforeEach(() => {
  if (typeof URL.createObjectURL !== "function") {
    Object.defineProperty(URL, "createObjectURL", {
      writable: true,
      value: vi.fn(() => "blob:mock-citation-image"),
    })
  } else {
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:mock-citation-image")
  }
  if (typeof URL.revokeObjectURL !== "function") {
    Object.defineProperty(URL, "revokeObjectURL", {
      writable: true,
      value: vi.fn(),
    })
  } else {
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined)
  }
})

describe("AssistantResponse cited sources", () => {
  it("links an inline citation marker to the authorized document viewer", () => {
    render(<AssistantResponse content="See the policy [S1]." citations={[citation]} />)

    const link = screen.getByRole("link", { name: "S1" })
    expect(link).toHaveAttribute("href", "/documents/doc-42?focus=unit%3Aunit-9")
    expect(link).toHaveAttribute("title", "Open handbook.pdf")
  })

  it("falls back to the chunk focus when a citation has no unit id", () => {
    const chunkCitation: ChatCitation = { ...citation, unit_id: null }
    render(<AssistantResponse content="See the policy [S1]." citations={[chunkCitation]} />)

    expect(screen.getByRole("link", { name: "S1" })).toHaveAttribute(
      "href",
      "/documents/doc-42?focus=chunk%3A7",
    )
  })

  it("groups citations into one source card per document", () => {
    const second: ChatCitation = { ...citation, id: "S2", chunk_index: 8, unit_id: "unit-10" }
    render(
      <AssistantResponse content="Answer [S1] and [S2]." citations={[citation, second]} />,
    )

    const cards = screen.getAllByRole("link").filter((link) =>
      (link.getAttribute("href") ?? "").includes("&focus="),
    )
    expect(cards).toHaveLength(1)
    expect(cards[0]).toHaveTextContent("handbook.pdf")
    expect(cards[0]?.getAttribute("href")).toBe(
      "/documents/doc-42?focus=unit%3Aunit-9&focus=unit%3Aunit-10",
    )
  })

  it("renders no source section when the answer has no citations", () => {
    render(<AssistantResponse content="I cannot answer that." citations={[]} />)
    expect(screen.queryAllByRole("link")).toHaveLength(0)
  })

  it("previews the citation image as the source card thumbnail", async () => {
    const imageCitation: ChatCitation = {
      ...citation,
      parser_version: "ocr-v1",
      image: { media_id: "9fe8d0ab12c34e5d.png", media_type: "image/png", width: 240, height: 120 },
    }
    const { container } = render(
      <AssistantResponse content="Kiến trúc [S1]." citations={[imageCitation]} />,
    )

    await waitFor(() => expect(loadCitationImageApi).toHaveBeenCalled())
    expect(loadCitationImageApi).toHaveBeenCalledWith(
      expect.anything(),
      "doc-42",
      "9fe8d0ab12c34e5d.png",
    )
    await waitFor(() => {
      expect(container.querySelector("img[aria-hidden]")).toBeTruthy()
    })
    const card = screen
      .getAllByRole("link")
      .find((link) => link.textContent?.includes("openCitedSource"))
    expect(card).toBeDefined()
    expect(card).toHaveTextContent("handbook.pdf")
  })

  it("keeps the file icon when the image preview fails to load", async () => {
    const imageCitation: ChatCitation = {
      ...citation,
      image: { media_id: "fail", media_type: "image/png" },
    }
    const { container } = render(
      <AssistantResponse content="Answer [S1]." citations={[imageCitation]} />,
    )

    await waitFor(() => {
      expect(
        screen
          .getAllByRole("link")
          .some((link) => link.textContent?.includes("openCitedSource")),
      ).toBe(true)
    })
    expect(screen.queryByRole("img")).toBeNull()
    expect(container.querySelector("img[aria-hidden]")).toBeNull()
  })

  it("renders a cited image preview card when an answer has image citations", async () => {
    const imageCitation: ChatCitation = {
      ...citation,
      parser_version: "ocr-v1",
      image: { media_id: "9fe8d0ab12c34e5d.png", media_type: "image/png", width: 240, height: 120 },
    }
    render(<AssistantResponse content="Kiến trúc [S1]." citations={[imageCitation]} />)

    await waitFor(() => {
      expect(screen.getByText("citedImages")).toBeDefined()
    })
    expect(screen.getByRole("button")).toBeDefined()
  })
})
