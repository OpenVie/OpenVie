import { render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { RoleAwareRoot } from "@/components/app/RoleAwareRoot"
import { useTokenRehydration } from "@/hooks/useTokenRehydration"

const replace = vi.fn()
const registrationStatus = vi.fn()

vi.mock("@/hooks/useTokenRehydration", () => ({
  useTokenRehydration: vi.fn(),
}))

vi.mock("@/i18n/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), prefetch: vi.fn() }),
}))

vi.mock("@/lib/auth-api", () => ({
  registrationStatusApi: () => registrationStatus(),
}))

vi.mock("@/components/chat/ChatPlayground", () => ({
  default: () => <div data-testid="chat-playground" />,
}))

const mockedStatus = vi.mocked(useTokenRehydration)

beforeEach(() => {
  replace.mockClear()
  registrationStatus.mockReset()
  window.localStorage.clear()
})

describe("RoleAwareRoot auth redirect", () => {
  it("sends logged-out visitors to the login route, keeping the destination", async () => {
    mockedStatus.mockReturnValue("unauthenticated")
    registrationStatus.mockResolvedValue({ setupRequired: false, selfRegistrationAllowed: false })
    window.history.replaceState({}, "", "/documents?tab=uploads")

    render(<RoleAwareRoot />)

    await vi.waitFor(() =>
      expect(replace).toHaveBeenCalledWith("/login?next=%2Fdocuments%3Ftab%3Duploads"),
    )
    expect(screen.queryByTestId("chat-playground")).not.toBeInTheDocument()
  });

  it("sends logged-out visitors to setup while the install is unclaimed", async () => {
    mockedStatus.mockReturnValue("unauthenticated")
    registrationStatus.mockResolvedValue({ setupRequired: true, selfRegistrationAllowed: false })

    render(<RoleAwareRoot />)

    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith("/setup"))
    expect(screen.queryByTestId("chat-playground")).not.toBeInTheDocument()
  });

  it("falls back to login when the registration status cannot be read", async () => {
    mockedStatus.mockReturnValue("unauthenticated")
    registrationStatus.mockRejectedValue(new Error("api down"))
    window.history.replaceState({}, "", "/")

    render(<RoleAwareRoot />)

    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith("/login?next=%2F"))
  });

  it("never redirects authenticated visitors", async () => {
    mockedStatus.mockReturnValue("authenticated")

    render(<RoleAwareRoot />)

    expect(replace).not.toHaveBeenCalled()
    expect(screen.getByTestId("chat-playground")).toBeInTheDocument()
  })
})
