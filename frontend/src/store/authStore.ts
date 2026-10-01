import { createStore } from 'zustand'
import type { AuthUser } from '@/types'

export interface AuthState {
  user: AuthUser | null
  accessToken: string | null
  tenantId: string | null
  setAuth: (user: AuthUser, token: string, tenantId: string) => void
  clearAuth: () => void
}

export const createAuthStore = () =>
  createStore<AuthState>()((set) => ({
    user: null,
    accessToken: null,
    tenantId: null,
    setAuth: (user, accessToken, tenantId) =>
      set({ user, accessToken, tenantId }),
    clearAuth: () =>
      set({ user: null, accessToken: null, tenantId: null }),
  }))
