import { render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { RoleAwareRoot } from "@/components/app/RoleAwareRoot"
import { useTokenRehydration } from "@/hooks/useTokenRehydration"

const replace = vi.fn()

vi.mock("@/hooks/useTokenRehydration", () => ({
  useTokenRehydration: vi.fn(),
}))

vi.mock("@/i18n/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), prefetch: vi.fn() }),
}))

vi.mock("@/components/chat/ChatPlayground", () => ({
  default: () => <div data-testid="chat-playground" />,
}))

const mockedStatus = vi.mocked(useTokenRehydration)

beforeEach(() => {
  replace.mockClear()
  window.localStorage.clear()
})

describe("RoleAwareRoot auth redirect", () => {
  it("sends logged-out visitors to the login route, keeping the destination", () => {
    mockedStatus.mockReturnValue("unauthenticated")
    window.history.replaceState({}, "", "/documents?tab=uploads")

    render(<RoleAwareRoot />)

    expect(replace).toHaveBeenCalledWith("/login?next=%2Fdocuments%3Ftab%3Duploads")
    expect(screen.queryByTestId("chat-playground")).not.toBeInTheDocument()
  })

  it("never redirects logged-out visitors to the chat itself", () => {
    mockedStatus.mockReturnValue("unauthenticated")
    window.history.replaceState({}, "", "/")

    render(<RoleAwareRoot />)

    expect(replace).toHaveBeenCalledWith("/login?next=%2F")
    expect(screen.queryByTestId("chat-playground")).not.toBeInTheDocument()
  })

  it("renders the chat only for authenticated sessions", () => {
    mockedStatus.mockReturnValue("authenticated")

    render(<RoleAwareRoot />)

    expect(replace).not.toHaveBeenCalled()
    expect(screen.getByTestId("chat-playground")).toBeInTheDocument()
  })
})
