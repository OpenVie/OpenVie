import { beforeEach, describe, expect, it } from "vitest"
import { consumeAuthDestination, rememberAuthDestination, safeInternalPath, withNext } from "@/lib/auth-redirect"

beforeEach(() => {
  window.localStorage.clear()
})

describe("auth destinations", () => {
  it("keeps safe localized destinations", () => {
    expect(safeInternalPath("/vi/documents?page=2")).toBe("/documents?page=2")
    expect(safeInternalPath("/en/documents#recent")).toBe("/documents#recent")
    expect(safeInternalPath("/vi")).toBe("/")
  })

  it("rejects open redirects", () => {
    expect(safeInternalPath("https://evil.example")).toBeNull()
    expect(safeInternalPath("//evil.example/path")).toBeNull()
    expect(safeInternalPath(null)).toBeNull()
    expect(withNext("/login", "//evil.example")).toBe("/login")
    expect(withNext("/login", "/documents")).toBe("/login?next=%2Fdocuments")
  })

  it("remembers the requested destination for the post-login redirect", () => {
    expect(rememberAuthDestination("/vi/documents")).toBe("/documents")
    expect(consumeAuthDestination()).toBe("/documents")
    expect(consumeAuthDestination()).toBe("/")
  })
})
