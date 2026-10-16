"use client"

import { useEffect, useRef } from "react"
import { Moon, Sun } from "lucide-react"
import { LanguageSwitcher } from "@/components/i18n/LanguageSwitcher"
import { useTheme } from "@/hooks/useTheme"

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  const rootRef = useRef<HTMLDivElement>(null)
  const { theme, toggleTheme } = useTheme()

  useEffect(() => {
    const root = rootRef.current
    const spotlight = root?.querySelector<HTMLElement>(".ov-auth-spotlight")
    if (!root || !spotlight || !window.matchMedia?.("(hover: hover)")?.matches) return

    function moveSpotlight(event: PointerEvent) {
      const bounds = root!.getBoundingClientRect()
      spotlight!.style.transform = `translate(${event.clientX - bounds.left - 280}px, ${event.clientY - bounds.top - 280}px)`
      spotlight!.classList.add("ov-auth-spotlight-visible")
    }
    function hideSpotlight() { spotlight!.classList.remove("ov-auth-spotlight-visible") }

    root.addEventListener("pointermove", moveSpotlight)
    root.addEventListener("pointerleave", hideSpotlight)
    return () => {
      root.removeEventListener("pointermove", moveSpotlight)
      root.removeEventListener("pointerleave", hideSpotlight)
    }
  }, [])

  return (
    <div ref={rootRef} className="ov-auth-page">
      <div className="ov-background-grid ov-auth-grid" aria-hidden="true" />
      <div className="ov-auth-aurora" aria-hidden="true">
        <i className="ov-ab1" />
        <i className="ov-ab2" />
        <i className="ov-ab3" />
        <i className="ov-ab4" />
      </div>
      <div className="ov-auth-spotlight" aria-hidden="true" />
      <div className="ov-auth-controls">
        <button
          type="button"
          className="ov-auth-theme"
          onClick={toggleTheme}
          aria-label={theme === "light" ? "Switch to dark mode" : "Switch to light mode"}
        >
          {theme === "light" ? <Moon className="size-4" /> : <Sun className="size-4" />}
          <span>{theme === "light" ? "Dark" : "Light"}</span>
        </button>
        <LanguageSwitcher className="ov-auth-language" variant="dark" />
      </div>
      <div className="ov-auth-content">{children}</div>
    </div>
  )
}
