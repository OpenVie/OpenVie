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
