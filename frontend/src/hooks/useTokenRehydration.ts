'use client'

import { useEffect, useState } from 'react'
import { useAuthStore } from '@/components/providers/StoreProvider'
import { refreshApi } from '@/lib/auth-api'

export type TokenRehydrationStatus =
  | 'rehydrating'
  | 'authenticated'
  | 'unauthenticated'

export function useTokenRehydration(): TokenRehydrationStatus {
  const accessToken = useAuthStore((s) => s.accessToken)
  const setAuth = useAuthStore((s) => s.setAuth)
  const clearAuth = useAuthStore((s) => s.clearAuth)
  const [status, setStatus] = useState<TokenRehydrationStatus>(() =>
    accessToken ? 'authenticated' : 'rehydrating'
  )

  useEffect(() => {
    if (accessToken) {
      return
    }

    let cancelled = false

    refreshApi()
      .then((data) => {
        if (cancelled) return
        setAuth(data.user, data.accessToken, data.user.tenantId)
        setStatus('authenticated')
      })
      .catch(() => {
        if (cancelled) return
        clearAuth()
        setStatus('unauthenticated')
      })

    return () => {
      cancelled = true
    }
  }, [accessToken, setAuth, clearAuth])

  if (accessToken) return 'authenticated'
  return status === 'authenticated' ? 'rehydrating' : status
}
