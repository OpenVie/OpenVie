import { beforeEach, describe, expect, it } from "vitest"
import { beginOidcAuthorization, consumeOidcTransaction } from "@/lib/oidc"

beforeEach(() => {
  window.sessionStorage.clear()
})

describe("OIDC browser transaction", () => {
  it("uses Authorization Code with PKCE S256 and saves only a one-time verifier", async () => {
    const authorizationUrl = new URL(
      await beginOidcAuthorization("http://localhost:3000/sso/callback"),
    )

    expect(authorizationUrl.origin).toBe("http://localhost:18080")
    expect(authorizationUrl.pathname).toBe("/realms/openvie/protocol/openid-connect/auth")
    expect(authorizationUrl.searchParams.get("client_id")).toBe("openvie-frontend")
    expect(authorizationUrl.searchParams.get("response_type")).toBe("code")
    expect(authorizationUrl.searchParams.get("code_challenge_method")).toBe("S256")
    expect(authorizationUrl.searchParams.get("code_challenge")).toMatch(/^[A-Za-z0-9_-]{43}$/)

    const state = authorizationUrl.searchParams.get("state")
    const transaction = consumeOidcTransaction(state)
    expect(transaction).toMatchObject({ redirectUri: "http://localhost:3000/sso/callback" })
    expect(transaction?.codeVerifier).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(consumeOidcTransaction(state)).toBeNull()
  })

  it("rejects a callback state that did not initiate the login", async () => {
    await beginOidcAuthorization("http://localhost:3000/sso/callback")

    expect(consumeOidcTransaction("attacker-state")).toBeNull()
  })
})
