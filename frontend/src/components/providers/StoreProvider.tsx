// components/providers/StoreProvider.tsx
'use client'

import { createContext, useContext, useState, type ReactNode } from 'react'
import { useStore } from 'zustand'
import { createAuthStore, type AuthState } from '@/store/authStore'

type AuthStore = ReturnType<typeof createAuthStore>

interface StoreContextValue {
  authStore: AuthStore
}

const StoreContext = createContext<StoreContextValue | null>(null)

export function StoreProvider({ children }: { children: ReactNode }) {
  const [stores] = useState<StoreContextValue>(() => ({
    authStore: createAuthStore(),
  }))

  return (
    <StoreContext.Provider value={stores}>
      {children}
    </StoreContext.Provider>
  )
}

// ─── Hooks ───────────────────────────────────────────────────────────────────

function useStoreContext() {
  const context = useContext(StoreContext)
  if (!context) throw new Error('Must be used within StoreProvider')
  return context
}

export function useAuthStore<T>(selector: (state: AuthState) => T): T {
  return useStore(useStoreContext().authStore, selector)
}
