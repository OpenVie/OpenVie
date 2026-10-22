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

"use client"

import { useEffect, useRef, useSyncExternalStore } from "react"
import { Moon, Sun } from "lucide-react"
import { LanguageSwitcher } from "@/components/i18n/LanguageSwitcher"
import { transitionTheme } from "@/lib/theme-transition"

type Theme = "light" | "dark"
const AUTH_THEME_EVENT = "openvie:auth-themechange"

function readStoredTheme(): Theme {
  if (typeof window === "undefined") return "light"
  return window.localStorage.getItem("openvie.theme") === "dark" ? "dark" : "light"
}

function subscribeToThemeChange(onStoreChange: () => void) {
  const onStorage = (event: StorageEvent) => {
    if (event.key === "openvie.theme") onStoreChange()
  }
  window.addEventListener(AUTH_THEME_EVENT, onStoreChange)
  window.addEventListener("storage", onStorage)
  return () => {
    window.removeEventListener(AUTH_THEME_EVENT, onStoreChange)
    window.removeEventListener("storage", onStorage)
  }
}

function serverThemeSnapshot(): Theme {
  return "light"
}

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  const rootRef = useRef<HTMLDivElement>(null)
  // The server snapshot keeps hydration deterministic; the browser then reads
  // the saved preference without rendering different initial markup.
  const theme = useSyncExternalStore(subscribeToThemeChange, readStoredTheme, serverThemeSnapshot)

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

  useEffect(() => {
    document.documentElement.dataset.ovTheme = theme
    document.documentElement.dataset.theme = theme
  }, [theme])

  function changeTheme(nextTheme: Theme) {
    transitionTheme(() => {
      window.localStorage.setItem("openvie.theme", nextTheme)
      window.dispatchEvent(new Event(AUTH_THEME_EVENT))
    })
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
