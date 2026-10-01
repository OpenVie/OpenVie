"use client"

import { Loader2 } from "lucide-react"
import { useEffect } from "react"
import ChatPlayground from "@/components/chat/ChatPlayground"
import { useTokenRehydration } from "@/hooks/useTokenRehydration"
import { useRouter } from "@/i18n/navigation"
import { withNext } from "@/lib/auth-redirect"

export function RoleAwareRoot() {
  const status = useTokenRehydration()
  const router = useRouter()

  useEffect(() => {
    if (status === "unauthenticated") {
      const destination = `${window.location.pathname}${window.location.search}${window.location.hash}`
      router.replace(withNext("/login", destination))
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
