import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { WorkspaceSwitcher } from "@/components/app/WorkspaceSwitcher"
import { useAuthStore } from "@/components/providers/StoreProvider"
import { listWorkspacesApi } from "@/lib/auth-api"
import type { AuthState } from "@/store/authStore"
import type { WorkspaceSummary } from "@/types"

vi.mock("@/components/providers/StoreProvider", () => ({
  useAuthStore: vi.fn(),
}))

vi.mock("@/lib/auth-api", () => ({
  listWorkspacesApi: vi.fn(),
  switchWorkspaceApi: vi.fn(),
}))

vi.mock("next-intl", () => ({
  useTranslations: () => (key: string) => key,
}))

const mockWorkspaces: WorkspaceSummary[] = [
  {
    id: "ws-1",
    name: "Engineering",
    slug: "engineering",
    role: "WORKSPACE_ADMIN",
    isDefault: true,
  },
  {
    id: "ws-2",
    name: "Design",
    slug: "design",
    role: "MEMBER",
    isDefault: false,
  },
]

describe("WorkspaceSwitcher", () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useAuthStore).mockImplementation(<T,>(selector: (state: AuthState) => T): T =>
      selector({
        user: {
          userId: "user-1",
          orgId: "org-1",
          activeWorkspaceId: "ws-1",
          fullName: "Ada Lovelace",
          email: "ada@acme.test",
          orgRole: "ORG_OWNER",
          workspaceRole: "WORKSPACE_ADMIN",
          mustChangePassword: false,
        },
        accessToken: "valid-token",
        setAuth: vi.fn(),
        clearAuth: vi.fn(),
      }),
    )
    vi.mocked(listWorkspacesApi).mockResolvedValue(mockWorkspaces)
  })

  it("renders active workspace name without throwing Base UI context errors", async () => {
    render(<WorkspaceSwitcher />)

    await waitFor(() => {
      expect(screen.getByText("Engineering")).toBeInTheDocument()
    })
  })

  it("opens menu with group label inside MenuGroup without error", async () => {
    render(<WorkspaceSwitcher />)

    await waitFor(() => {
      expect(screen.getByText("Engineering")).toBeInTheDocument()
    })

    const trigger = screen.getByRole("button", { name: "label" })
    fireEvent.click(trigger)

    await waitFor(() => {
      expect(screen.getByText("workspaces")).toBeInTheDocument()
      expect(screen.getByText("Design")).toBeInTheDocument()
    })
  })
})
