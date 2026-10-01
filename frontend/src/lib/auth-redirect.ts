const AUTH_DESTINATION_KEY = "openvie:auth-destination"

function withoutUiLocale(pathname: string): string {
  if (pathname === "/en" || pathname === "/vi") return "/"
  if (pathname.startsWith("/en/") || pathname.startsWith("/vi/")) return pathname.slice(3)
  return pathname
}

export function safeInternalPath(value: string | null | undefined): string | null {
  if (!value || !value.startsWith("/") || value.startsWith("//")) return null

  try {
    const url = new URL(value, "http://openvie.local")
    if (url.origin !== "http://openvie.local") return null
    return `${withoutUiLocale(url.pathname)}${url.search}${url.hash}`
  } catch {
    return null
  }
}

export function rememberAuthDestination(value: string | null | undefined): string | null {
  const destination = safeInternalPath(value)
  if (destination && typeof window !== "undefined") {
    window.localStorage.setItem(AUTH_DESTINATION_KEY, destination)
  }
  return destination
}

export function getAuthDestination(
  value?: string | null,
  fallback = "/",
): string {
  const requested = rememberAuthDestination(value)
  if (requested) return requested

  if (typeof window !== "undefined") {
    const stored = safeInternalPath(window.localStorage.getItem(AUTH_DESTINATION_KEY))
    if (stored) return stored
  }

  return fallback
}

export function consumeAuthDestination(fallback = "/"): string {
  const destination = getAuthDestination(null, fallback)
  if (typeof window !== "undefined") {
    window.localStorage.removeItem(AUTH_DESTINATION_KEY)
  }
  return destination
}

export function withNext(path: string, destination: string | null): string {
  const safeDestination = safeInternalPath(destination)
  if (!safeDestination) return path
  const separator = path.includes("?") ? "&" : "?"
  return `${path}${separator}next=${encodeURIComponent(safeDestination)}`
}
