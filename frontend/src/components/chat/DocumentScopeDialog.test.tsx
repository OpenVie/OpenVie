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

import * as React from "react"
import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import { MAX_SCOPE_DOCUMENTS, DocumentScopeDialog, type ScopeDocument } from "@/components/chat/DocumentScopeDialog"

vi.mock("next-intl", () => ({
  useTranslations: () => (key: string) => key,
}))

vi.mock("@/hooks/useTheme", () => ({
  useTheme: () => ({ theme: "light", setTheme: vi.fn(), toggleTheme: vi.fn() }),
}))

vi.mock("@base-ui/react/dialog", () => ({
  Dialog: {
    Root: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    Portal: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    Backdrop: (props: React.ComponentProps<"div">) => <div {...props} />,
    Popup: (props: React.ComponentProps<"div">) => <div role="dialog" {...props} />,
    Title: (props: React.ComponentProps<"h2">) => <h2 {...props} />,
    Description: (props: React.ComponentProps<"p">) => <p {...props} />,
    Close: (props: React.ComponentProps<"button">) => <button {...props} />,
  },
}))

const documents: ScopeDocument[] = [
  { id: "doc-1", fileName: "Paid leave policy.pdf", status: "COMPLETED" },
  { id: "doc-2", fileName: "Reimbursement handbook.pdf", status: "COMPLETED" },
  { id: "doc-3", fileName: "Draft contract.docx", status: "PROCESSING" },
]

describe("DocumentScopeDialog", () => {
  it("lists documents ready for answering with a searchable multiselect", () => {
    render(
      <DocumentScopeDialog
        open
        onOpenChange={vi.fn()}
        documents={documents}
        selected={[]}
        onApply={vi.fn()}
      />,
    )

    expect(screen.getByRole("dialog")).toBeInTheDocument()
    expect(screen.getByRole("option", { name: /Paid leave policy.pdf/ })).not.toBeDisabled()
    expect(screen.getByRole("option", { name: /Draft contract.docx/ })).toBeDisabled()

    const search = screen.getByRole("textbox")
    fireEvent.change(search, { target: { value: "reimbursement" } })
    expect(screen.getByRole("option", { name: /Reimbursement handbook.pdf/ })).toBeInTheDocument()
    expect(screen.queryByRole("option", { name: /Paid leave policy.pdf/ })).not.toBeInTheDocument()
  })

  it("selects completed documents only and applies the picked scope", () => {
    const onApply = vi.fn()
    render(
      <DocumentScopeDialog
        open
        onOpenChange={vi.fn()}
        documents={documents}
        selected={[]}
        onApply={onApply}
      />,
    )

    fireEvent.click(screen.getByRole("option", { name: /Paid leave policy.pdf/ }))
    fireEvent.click(screen.getByRole("option", { name: /Reimbursement handbook.pdf/ }))
    fireEvent.click(screen.getByRole("option", { name: /Draft contract.docx/ }))

    expect(
      screen.getByRole("option", { name: /Paid leave policy.pdf/ }),
    ).toHaveAttribute("aria-selected", "true")
    expect(
      screen.getByRole("option", { name: /Draft contract.docx/ }),
    ).toHaveAttribute("aria-selected", "false")

    fireEvent.click(screen.getByRole("button", { name: "scopeApply" }))
    expect(onApply).toHaveBeenCalledWith(["doc-1", "doc-2"])
  })

  it("keeps the scope selection bounded to the maximum", () => {
    const onApply = vi.fn()
    render(
      <DocumentScopeDialog
        open
        onOpenChange={vi.fn()}
        documents={Array.from({ length: MAX_SCOPE_DOCUMENTS + 1 }, (_, index): ScopeDocument => ({
          id: `doc-${index}`,
          fileName: `doc-${index}.pdf`,
          status: "COMPLETED",
        }))}
        selected={[]}
        onApply={onApply}
      />,
    )

    for (let index = 0; index < MAX_SCOPE_DOCUMENTS; index += 1) {
      fireEvent.click(screen.getByRole("option", { name: new RegExp(`^doc-${index}\\.pdf$`) }))
    }
    const overflow = screen.getByRole("option", { name: new RegExp(`^doc-${MAX_SCOPE_DOCUMENTS}\\.pdf$`) })
    expect(overflow).toBeDisabled()
    overflow.click()

    fireEvent.click(screen.getByRole("button", { name: "scopeApply" }))
    expect(onApply).toHaveBeenCalledWith(
      Array.from({ length: MAX_SCOPE_DOCUMENTS }, (_, index) => `doc-${index}`),
    )
  })
})
