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
