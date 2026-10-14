"use client"

import { useEffect, useRef, useState } from "react"
import { Moon, Sun } from "lucide-react"
import { LanguageSwitcher } from "@/components/i18n/LanguageSwitcher"

type Theme = "light" | "dark"

function readStoredTheme(): Theme {
  if (typeof window === "undefined") return "light"
  return window.localStorage.getItem("openvie.theme") === "dark" ? "dark" : "light"
}

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  const rootRef = useRef<HTMLDivElement>(null)
  const [theme, setTheme] = useState<Theme>(readStoredTheme)

  useEffect(() => {
    const root = rootRef.current
    const spotlight = root?.querySelector<HTMLElement>(".ov-auth-spotlight")
    if (!root || !spotlight || !window.matchMedia("(hover: hover)").matches) return

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

  function changeTheme(nextTheme: Theme) {
    setTheme(nextTheme)
    document.documentElement.dataset.ovTheme = nextTheme
    document.documentElement.dataset.theme = nextTheme
    window.localStorage.setItem("openvie.theme", nextTheme)
    window.dispatchEvent(new Event("openvie:themechange"))
  }

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
          onClick={() => changeTheme(theme === "light" ? "dark" : "light")}
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
