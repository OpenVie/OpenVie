"use client"

import { Loader2 } from "lucide-react"
import { useEffect } from "react"
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
    return (
      <div className="grid min-h-dvh place-items-center bg-slate-100">
        <Loader2 className="size-8 animate-spin text-indigo-600" />
      </div>
    )
  }

  return <ChatPlayground />
}
