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

import { render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"
import { ProtectedComposerShell } from "./AppShell"
import { useTokenRehydration } from "@/hooks/useTokenRehydration"
import { useAuthStore } from "@/components/providers/StoreProvider"

const replace = vi.fn()
vi.mock("next-intl", () => ({
  useTranslations: () => (key: string) => key,
}))


vi.mock("@/hooks/useTokenRehydration", () => ({
  useTokenRehydration: vi.fn(),
}))

vi.mock("@/components/providers/StoreProvider", () => ({
  useAuthStore: vi.fn(),
}))

vi.mock("@/i18n/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/admin/apps/compose",
  Link: ({ children, href, ...rest }: { children: React.ReactNode; href: string }) => (
    <a href={href} {...rest}>{children}</a>
  ),
}))

const mockedStatus = vi.mocked(useTokenRehydration)
const mockedAuth = vi.mocked(useAuthStore)

beforeEach(() => {
  replace.mockClear()
  window.localStorage.clear()
})

describe("ProtectedComposerShell", () => {
  it("renders children in full screen container for ORG_OWNER without sidebar", () => {
    mockedStatus.mockReturnValue("authenticated")
    mockedAuth.mockImplementation(((selector: (state: { user: { orgRole: string } | null }) => unknown) =>
      selector({ user: { orgRole: "ORG_OWNER" } })) as typeof useAuthStore)

    const { container } = render(
      <ProtectedComposerShell>
        <div data-testid="composer-canvas">Canvas Workspace</div>
      </ProtectedComposerShell>
    )

    expect(screen.getByTestId("composer-canvas")).toBeInTheDocument()
    const shell = container.firstChild as HTMLElement
    expect(shell.className).toContain("h-dvh")
    expect(shell.className).toContain("w-screen")
    expect(shell.className).toContain("overflow-hidden")
    // Sidenavbar should NOT exist in composer shell
    expect(container.querySelector("aside.ov-sidebar")).toBeNull()
  })

  it("redirects unauthenticated users to login", async () => {
    mockedStatus.mockReturnValue("unauthenticated")
    mockedAuth.mockImplementation(((selector: (state: { user: unknown }) => unknown) =>
      selector({ user: null })) as typeof useAuthStore)

    render(
      <ProtectedComposerShell>
        <div data-testid="composer-canvas">Canvas Workspace</div>
      </ProtectedComposerShell>
    )

    expect(screen.queryByTestId("composer-canvas")).toBeNull()
    await vi.waitFor(() => expect(replace).toHaveBeenCalled())
  })

  it("blocks non-owners with an admin alert", () => {
    mockedStatus.mockReturnValue("authenticated")
    mockedAuth.mockImplementation(((selector: (state: { user: { orgRole: string } | null }) => unknown) =>
      selector({ user: { orgRole: "MEMBER" } })) as typeof useAuthStore)

    render(
      <ProtectedComposerShell>
        <div data-testid="composer-canvas">Canvas Workspace</div>
      </ProtectedComposerShell>
    )

    expect(screen.queryByTestId("composer-canvas")).toBeNull()
    expect(screen.getByRole("alert")).toBeInTheDocument()
  })
})
