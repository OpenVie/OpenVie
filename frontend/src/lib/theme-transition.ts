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

import { flushSync } from "react-dom"

type ViewTransitionDocument = Document & {
  startViewTransition?: (update: () => void) => { finished: Promise<void> }
}

/** Cross-fades a synchronous theme update when the browser supports View Transitions. */
export function transitionTheme(update: () => void) {
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    update()
    return
  }

  const documentWithTransition = document as ViewTransitionDocument
  if (!documentWithTransition.startViewTransition) {
    update()
    return
  }

  void documentWithTransition.startViewTransition(() => {
    flushSync(update)
  }).finished.catch(() => undefined)
}
