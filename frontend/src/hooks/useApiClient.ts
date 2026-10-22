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

'use client';

import { useCallback, useRef } from 'react'
import { useTranslations } from 'next-intl'
import { useAuthStore } from '@/components/providers/StoreProvider'
import { getApiBase } from '@/lib/auth-api'
import { useRouter } from '@/i18n/navigation'
import { withNext } from '@/lib/auth-redirect'

export function useApiClient() {
  const t = useTranslations('Common')
  const router = useRouter()
  const accessToken = useAuthStore(s => s.accessToken)
  const setAuth = useAuthStore(s => s.setAuth)
  const clearAuth = useAuthStore(s => s.clearAuth)

  const isRefreshingRef = useRef(false)
  const failedQueueRef = useRef<Array<{
    resolve: (token: string) => void
    reject: (err: unknown) => void
  }>>([])

  const processQueue = (error: unknown, token: string | null) => {
    failedQueueRef.current.forEach(p =>
      error ? p.reject(error) : p.resolve(token!)
    )
    failedQueueRef.current = []
  }

  const request = useCallback(async (
    endpoint: string,
    options: RequestInit = {}
  ): Promise<Response> => {

    const makeRequest = (token: string | null) => {
      const headers = new Headers(options.headers)
      if (!(options.body instanceof FormData) && !headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json')
      }
      if (token) {
        headers.set('Authorization', `Bearer ${token}`)
      }

      return fetch(endpoint, {
        ...options,
        headers,
        credentials: 'include',
      })
    }

    let response = await makeRequest(accessToken)

    if (response.status !== 401) return response

    // If already refreshing — queue this request
    if (isRefreshingRef.current) {
      return new Promise<string>((resolve, reject) => {
        failedQueueRef.current.push({ resolve, reject })
      }).then(newToken => makeRequest(newToken))
    }

    isRefreshingRef.current = true

    try {
      const refreshResponse = await fetch(`${getApiBase()}/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
      })

      if (!refreshResponse.ok) throw new Error('Refresh failed')

      const { accessToken: newToken, user } = await refreshResponse.json()
      setAuth(user, newToken)
      processQueue(null, newToken)

      response = await makeRequest(newToken)
    } catch {
      const sessionError = new Error(t('sessionExpired'))
      processQueue(sessionError, null)
      clearAuth()
      const destination = `${window.location.pathname}${window.location.search}${window.location.hash}`
      router.replace(withNext('/login', destination))
      throw sessionError
    } finally {
      isRefreshingRef.current = false
    }

    return response
  }, [accessToken, setAuth, clearAuth, router, t])

  return { request }
}
