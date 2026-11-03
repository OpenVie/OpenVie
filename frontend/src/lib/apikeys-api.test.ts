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

import { beforeAll, describe, expect, it } from "vitest"

import type { ApiRequest } from "@/lib/api-request"
import {
  createApiKeyApi,
  listApiKeysApi,
  revokeApiKeyApi,
} from "@/lib/apikeys-api"

beforeAll(() => {
  process.env.NEXT_PUBLIC_API_BASE_URL = "http://api.test/api/v1"
})

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  })
}

describe("api-keys API", () => {
  it("lists masked keys from the admin base", async () => {
    const endpoints: string[] = []
    const request: ApiRequest = async (endpoint) => {
      endpoints.push(endpoint)
      return jsonResponse([{
        id: "key-1",
        workspaceId: "ws-1",
        serviceUserId: "svc-1",
        name: "DMS bridge",
        keyPrefix: "ovk_7Yq3",
        role: "MEMBER",
        status: "ACTIVE",
        createdAt: "2026-01-01T00:00:00Z",
        lastUsedAt: null,
        expiresAt: null,
        createdBy: "owner-1",
        expired: false,
      }])
    }

    const keys = await listApiKeysApi(request)

    expect(endpoints).toEqual(["http://api.test/api/v1/admin/api-keys"])
    expect(keys).toHaveLength(1)
    expect(keys[0]?.keyPrefix).toBe("ovk_7Yq3")
  })

  it("creates a key omitting expiresAt when unset", async () => {
    const endpoints: string[] = []
    const bodies: unknown[] = []
    const request: ApiRequest = async (endpoint, init) => {
      endpoints.push(endpoint)
      bodies.push(init?.body)
      return jsonResponse({
        id: "key-1",
        key: "ovk_secret-shown-once",
        keyView: { id: "key-1" },
      })
    }

    const created = await createApiKeyApi(request, {
      name: "DMS bridge",
      workspaceId: "ws-1",
      role: "MEMBER",
    })

    expect(endpoints).toEqual(["http://api.test/api/v1/admin/api-keys"])
    expect(bodies[0]).toBe(JSON.stringify({ name: "DMS bridge", workspaceId: "ws-1", role: "MEMBER" }))
    expect(created.key).toBe("ovk_secret-shown-once")
  })

  it("creates a key with expiry", async () => {
    const bodies: string[] = []
    const request: ApiRequest = async (_endpoint, init) => {
      bodies.push(String(init?.body))
      return jsonResponse({ id: "key-1", key: "ovk_x", keyView: { id: "key-1" } })
    }

    await createApiKeyApi(request, {
      name: "Trial bridge",
      workspaceId: "ws-2",
      role: "WORKSPACE_ADMIN",
      expiresAt: "2026-12-31T09:00:00Z",
    })

    expect(bodies[0]).toBe(JSON.stringify({
      name: "Trial bridge",
      workspaceId: "ws-2",
      role: "WORKSPACE_ADMIN",
      expiresAt: "2026-12-31T09:00:00Z",
    }))
  })

  it("revokes a key with DELETE", async () => {
    const endpoints: string[] = []
    const request: ApiRequest = async (endpoint) => {
      endpoints.push(endpoint)
      return new Response(null, { status: 204 })
    }

    await revokeApiKeyApi(request, "key-1")

    expect(endpoints).toEqual(["http://api.test/api/v1/admin/api-keys/key-1"])
  })
})
