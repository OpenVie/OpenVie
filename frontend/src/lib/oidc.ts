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

const OIDC_TRANSACTION_KEY = "openvie:oidc-transaction"

export type OidcConfiguration = {
  issuer: string
  clientId: string
}

type OidcTransaction = {
  state: string
  codeVerifier: string
  redirectUri: string
}

function base64Url(bytes: Uint8Array): string {
  let binary = ""
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte)
  })
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

function secureRandomValue(): string {
  if (typeof window === "undefined" || !window.crypto?.getRandomValues) {
    throw new Error("Secure browser cryptography is unavailable")
  }

  const random = new Uint8Array(32)
  window.crypto.getRandomValues(random)
  return base64Url(random)
}

async function codeChallenge(verifier: string): Promise<string> {
  if (typeof window === "undefined" || !window.crypto?.subtle) {
    throw new Error("Secure browser cryptography is unavailable")
  }

  const digest = await window.crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))
  return base64Url(new Uint8Array(digest))
}

export function oidcConfiguration(): OidcConfiguration {
  return {
    issuer: (process.env.NEXT_PUBLIC_OIDC_ISSUER ?? "http://localhost:18080/realms/openvie").replace(/\/$/, ""),
    clientId: process.env.NEXT_PUBLIC_OIDC_CLIENT_ID ?? "openvie-frontend",
  }
}

/**
 * Builds the Keycloak authorization URL and persists the one-time data needed
 * by the callback. Tokens never enter browser storage: only a PKCE verifier
 * and anti-CSRF state are retained for the redirect round trip.
 */
export async function beginOidcAuthorization(redirectUri: string): Promise<string> {
  if (typeof window === "undefined") {
    throw new Error("OIDC authorization can only start in a browser")
  }

  const state = secureRandomValue()
  const codeVerifier = secureRandomValue()
  const { issuer, clientId } = oidcConfiguration()
  const params = new URLSearchParams({
    client_id: clientId,
    code_challenge: await codeChallenge(codeVerifier),
    code_challenge_method: "S256",
    redirect_uri: redirectUri,
    response_type: "code",
    scope: "openid profile email",
    state,
  })

  window.sessionStorage.setItem(
    OIDC_TRANSACTION_KEY,
    JSON.stringify({ state, codeVerifier, redirectUri } satisfies OidcTransaction),
  )

  return `${issuer}/protocol/openid-connect/auth?${params.toString()}`
}

/**
 * Returns the transaction only for the state that initiated this browser
 * redirect. It is consumed exactly once, before the authorization code is
 * exchanged with the application backend.
 */
export function consumeOidcTransaction(receivedState: string | null): OidcTransaction | null {
  if (typeof window === "undefined") return null

  const raw = window.sessionStorage.getItem(OIDC_TRANSACTION_KEY)
  window.sessionStorage.removeItem(OIDC_TRANSACTION_KEY)
  if (!raw || !receivedState) return null

  try {
    const transaction = JSON.parse(raw) as Partial<OidcTransaction>
    if (
      transaction.state !== receivedState ||
      typeof transaction.codeVerifier !== "string" ||
      typeof transaction.redirectUri !== "string"
    ) {
      return null
    }
    return {
      state: transaction.state,
      codeVerifier: transaction.codeVerifier,
      redirectUri: transaction.redirectUri,
    }
  } catch {
    return null
  }
}
