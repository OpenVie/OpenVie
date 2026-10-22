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
