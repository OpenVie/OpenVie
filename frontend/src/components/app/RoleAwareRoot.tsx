"use client"

import { useEffect } from "react"
import { AppLoadingScreen } from "@/components/app/AppLoadingScreen"
import ChatPlayground from "@/components/chat/ChatPlayground"
import { useTokenRehydration } from "@/hooks/useTokenRehydration"
import { useRouter } from "@/i18n/navigation"
import { registrationStatusApi } from "@/lib/auth-api"
import { withNext } from "@/lib/auth-redirect"

/**
 * Chat entry for signed-in users. Unauthenticated visitors go to the setup
 * page while the installation is unclaimed, and to login afterwards.
 */
export function RoleAwareRoot() {
  const status = useTokenRehydration()
  const router = useRouter()

  useEffect(() => {
    if (status !== "unauthenticated") return
    const destination = `${window.location.pathname}${window.location.search}${window.location.hash}`
    let active = true
    registrationStatusApi()
      .then((registration) => {
        if (!active) return
        router.replace(registration.setupRequired ? "/setup" : withNext("/login", destination))
      })
      .catch(() => {
        if (active) router.replace(withNext("/login", destination))
      })
    return () => {
      active = false
    }
  }, [router, status])

  if (status !== "authenticated") {
    return <AppLoadingScreen label="Loading" />
  }

  return <ChatPlayground />
}
