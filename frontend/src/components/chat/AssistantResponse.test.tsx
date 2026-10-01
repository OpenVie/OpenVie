import { render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

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
})
