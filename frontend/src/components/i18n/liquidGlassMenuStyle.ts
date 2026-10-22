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

import type { CSSProperties } from "react"

export type LiquidGlassTheme = "light" | "dark"

export function liquidGlassMenuStyle(theme: LiquidGlassTheme): CSSProperties {
  if (theme === "dark") {
    return {
      background: "rgb(18 22 26 / .96)",
      border: "1px solid rgb(255 255 255 / .14)",
      boxShadow: "0 18px 42px -16px rgb(0 0 0 / .7)",
      color: "#e6ebe4",
      backdropFilter: "blur(18px) saturate(140%)",
      WebkitBackdropFilter: "blur(18px) saturate(140%)",
    }
  }

  return {
    background: "rgb(255 255 255 / .96)",
    border: "1px solid rgb(16 24 14 / .14)",
    boxShadow: "0 18px 42px -16px rgb(16 24 14 / .28)",
    color: "#26322a",
    backdropFilter: "blur(18px) saturate(140%)",
    WebkitBackdropFilter: "blur(18px) saturate(140%)",
  }
}

/**
 * Liquid-glass surface for chat popups: heavier blur and a specular top edge
 * so the panel reads as glass over arbitrary page content. Pair with the same
 * `html[data-theme="light"]` layering used by the menus when overriding parts
 * of it in CSS instead.
 */
export function liquidGlassPopupStyle(theme: LiquidGlassTheme): CSSProperties {
  if (theme === "dark") {
    return {
      background: "linear-gradient(180deg, rgb(28 33 38 / .82), rgb(18 22 26 / .88))",
      border: "1px solid rgb(255 255 255 / .14)",
      boxShadow:
        "inset 0 1px 0 rgb(255 255 255 / .18), inset 0 0 0 1px rgb(255 255 255 / .03), 0 24px 70px -18px rgb(0 0 0 / .8)",
      color: "#e6ebe4",
      backdropFilter: "blur(28px) saturate(160%)",
      WebkitBackdropFilter: "blur(28px) saturate(160%)",
    }
  }

  return {
    background: "linear-gradient(180deg, rgb(255 255 255 / .82), rgb(255 255 255 / .7))",
    border: "1px solid rgb(255 255 255 / .65)",
    boxShadow:
      "inset 0 1px 0 rgb(255 255 255 / .9), inset 0 0 0 1px rgb(255 255 255 / .35), 0 24px 70px -18px rgb(16 24 14 / .35)",
    color: "#26322a",
    backdropFilter: "blur(28px) saturate(160%)",
    WebkitBackdropFilter: "blur(28px) saturate(160%)",
  }
}
