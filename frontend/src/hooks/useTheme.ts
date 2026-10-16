"use client"

import { useEffect, useState } from "react"

export type Theme = "light" | "dark"

export const THEME_KEY = "openvie.theme"
export const THEME_CHANGE_EVENT = "openvie:themechange"

export function getStoredTheme(): Theme {
  if (typeof window === "undefined") return "light"
  return window.localStorage.getItem(THEME_KEY) === "dark" ? "dark" : "light"
}

export function applyTheme(nextTheme: Theme) {
  if (typeof window === "undefined") return
  document.documentElement.dataset.ovTheme = nextTheme
  document.documentElement.dataset.theme = nextTheme
  window.localStorage.setItem(THEME_KEY, nextTheme)
  if (typeof queueMicrotask === "function") {
    queueMicrotask(() => window.dispatchEvent(new Event(THEME_CHANGE_EVENT)))
  } else {
    window.dispatchEvent(new Event(THEME_CHANGE_EVENT))
  }
}
export function useTheme() {
  const [theme, setTheme] = useState<Theme>("light")

  useEffect(() => {
    function syncTheme() {
      const stored = window.localStorage.getItem(THEME_KEY)
      if (stored === "dark" || stored === "light") {
        setTheme(stored)
      }
    }
    syncTheme()
    window.addEventListener(THEME_CHANGE_EVENT, syncTheme)
    window.addEventListener("storage", syncTheme)
    return () => {
      window.removeEventListener(THEME_CHANGE_EVENT, syncTheme)
      window.removeEventListener("storage", syncTheme)
    }
  }, [])

  function changeTheme(nextTheme: Theme | ((current: Theme) => Theme)) {
    const currentTheme =
      (typeof window !== "undefined" && (window.localStorage.getItem(THEME_KEY) as Theme)) || theme
    const resolvedTheme = typeof nextTheme === "function" ? nextTheme(currentTheme) : nextTheme
    setTheme(resolvedTheme)
    applyTheme(resolvedTheme)
  }

  function toggleTheme() {
    const currentTheme =
      (typeof window !== "undefined" && (window.localStorage.getItem(THEME_KEY) as Theme)) || theme
    const nextTheme: Theme = currentTheme === "light" ? "dark" : "light"
    changeTheme(nextTheme)
  }

  return {
    theme,
    setTheme: changeTheme,
    toggleTheme,
  }
}
