import { createStore } from 'zustand'
import type { AuthUser } from '@/types'

export interface AuthState {
  user: AuthUser | null
  accessToken: string | null
  setAuth: (user: AuthUser, token: string) => void
  clearAuth: () => void
}

export const createAuthStore = () =>
  createStore<AuthState>()((set) => ({
    user: null,
    accessToken: null,
    setAuth: (user, accessToken) => set({ user, accessToken }),
    clearAuth: () => set({ user: null, accessToken: null }),
  }))
