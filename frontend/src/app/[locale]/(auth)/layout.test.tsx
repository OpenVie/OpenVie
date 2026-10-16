import { fireEvent, render, screen } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import AuthLayout from "./layout"
import { THEME_KEY } from "@/hooks/useTheme"

vi.mock("@/components/i18n/LanguageSwitcher", () => ({
  LanguageSwitcher: () => <div data-testid="language-switcher" />,
}))

describe("AuthLayout", () => {
  beforeEach(() => {
    window.localStorage.clear()
    delete document.documentElement.dataset.ovTheme
    delete document.documentElement.dataset.theme
    window.matchMedia = vi.fn().mockImplementation((query) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
  })

  afterEach(() => {
    window.localStorage.clear()
  })

  it("renders light mode button initially for default state", () => {
    render(
      <AuthLayout>
        <div>Content</div>
      </AuthLayout>,
    )

    const button = screen.getByRole("button", { name: "Switch to dark mode" })
    expect(button).toBeInTheDocument()
    expect(screen.getByText("Dark")).toBeInTheDocument()
  })

  it("syncs to dark mode when stored in localStorage without hydration error", () => {
    window.localStorage.setItem(THEME_KEY, "dark")

    render(
      <AuthLayout>
        <div>Content</div>
      </AuthLayout>,
    )

    const button = screen.getByRole("button", { name: "Switch to light mode" })
    expect(button).toBeInTheDocument()
    expect(screen.getByText("Light")).toBeInTheDocument()
    // Stored theme must not be wiped by mount effect
    expect(window.localStorage.getItem(THEME_KEY)).toBe("dark")
  })

  it("toggles theme when button is clicked", () => {
    render(
      <AuthLayout>
        <div>Content</div>
      </AuthLayout>,
    )

    const button = screen.getByRole("button", { name: "Switch to dark mode" })
    fireEvent.click(button)

    expect(screen.getByRole("button", { name: "Switch to light mode" })).toBeInTheDocument()
    expect(screen.getByText("Light")).toBeInTheDocument()
    expect(window.localStorage.getItem(THEME_KEY)).toBe("dark")
    expect(document.documentElement.dataset.ovTheme).toBe("dark")
  })
})
