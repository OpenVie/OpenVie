import type { CSSProperties } from "react"

export type LiquidGlassTheme = "light" | "dark"

export function liquidGlassMenuStyle(theme: LiquidGlassTheme): CSSProperties {
  if (theme === "dark") {
    return {
      background: "linear-gradient(145deg, rgb(255 255 255 / .06), rgb(255 255 255 / .02) 52%, rgb(255 255 255 / .04))",
      border: "1px solid rgb(255 255 255 / .18)",
      boxShadow: "inset 0 1px 0 rgb(255 255 255 / .12), inset 0 -1px 0 rgb(255 255 255 / .04), 0 24px 48px -8px rgb(0 0 0 / .4), 0 8px 16px -4px rgb(0 0 0 / .2)",
      color: "#e6ebe4",
      backdropFilter: "blur(40px) saturate(180%) brightness(110%)",
      WebkitBackdropFilter: "blur(40px) saturate(180%) brightness(110%)",
    }
  }

  return {
    background: "linear-gradient(145deg, rgb(255 255 255 / .35), rgb(255 255 255 / .12) 52%, rgb(255 255 255 / .2))",
    border: "1px solid rgb(255 255 255 / .5)",
    boxShadow: "inset 0 1px 0 rgb(255 255 255 / .6), inset 0 -1px 0 rgb(62 79 57 / .06), 0 24px 48px -8px rgb(56 73 50 / .15), 0 8px 16px -4px rgb(56 73 50 / .08)",
    color: "#26322a",
    backdropFilter: "blur(40px) saturate(180%) brightness(105%)",
    WebkitBackdropFilter: "blur(40px) saturate(180%) brightness(105%)",
  }
}
