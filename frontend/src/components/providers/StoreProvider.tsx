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
