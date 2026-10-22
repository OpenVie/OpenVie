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

import { act, renderHook } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { THEME_CHANGE_EVENT, THEME_KEY, useTheme } from "./useTheme"

describe("useTheme hook", () => {
  beforeEach(() => {
    window.localStorage.clear()
    delete document.documentElement.dataset.ovTheme
    delete document.documentElement.dataset.theme
  })

  afterEach(() => {
    window.localStorage.clear()
  })

  it("defaults initial theme to light for SSR compatibility", () => {
    window.localStorage.setItem(THEME_KEY, "dark")
    const { result } = renderHook(() => useTheme())

    // Initial render before effect runs or immediately on mount
    expect(result.current.theme).toBe("dark")
  })

  it("syncs stored theme from localStorage on mount without destroying dark preference", () => {
    window.localStorage.setItem(THEME_KEY, "dark")
    const { result } = renderHook(() => useTheme())

    expect(window.localStorage.getItem(THEME_KEY)).toBe("dark")
    expect(result.current.theme).toBe("dark")
  })

  it("toggles theme and updates localStorage, dataset, and dispatches event", async () => {
    const eventSpy = vi.fn()
    window.addEventListener(THEME_CHANGE_EVENT, eventSpy)

    const { result } = renderHook(() => useTheme())
    expect(result.current.theme).toBe("light")

    act(() => {
      result.current.toggleTheme()
    })
    await Promise.resolve()
    expect(result.current.theme).toBe("dark")
    expect(window.localStorage.getItem(THEME_KEY)).toBe("dark")
    expect(document.documentElement.dataset.ovTheme).toBe("dark")
    expect(document.documentElement.dataset.theme).toBe("dark")
    expect(eventSpy).toHaveBeenCalled()

    act(() => {
      result.current.toggleTheme()
    })
    await Promise.resolve()
    expect(result.current.theme).toBe("light")
    expect(window.localStorage.getItem(THEME_KEY)).toBe("light")
    expect(document.documentElement.dataset.ovTheme).toBe("light")
    expect(document.documentElement.dataset.theme).toBe("light")

    window.removeEventListener(THEME_CHANGE_EVENT, eventSpy)
  })

  it("responds to themechange events from other components", () => {
    const { result } = renderHook(() => useTheme())
    expect(result.current.theme).toBe("light")

    act(() => {
      window.localStorage.setItem(THEME_KEY, "dark")
      window.dispatchEvent(new Event(THEME_CHANGE_EVENT))
    })

    expect(result.current.theme).toBe("dark")
  })
})
