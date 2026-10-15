"use client"

import { Loader2 } from "lucide-react"

type AppLoadingScreenProps = {
  label: string
}
export function AppLoadingScreen({ label }: AppLoadingScreenProps) {
  return (
    <div className="ov-loading-screen" role="status" aria-live="polite">
      <div className="ov-background-grid" aria-hidden="true" />
      <div className="ov-aurora" aria-hidden="true"><i /><i /><i /><i /></div>
      <Loader2 className="ov-loading-spinner size-7 animate-spin" aria-label={label} />
    </div>
  )
}
