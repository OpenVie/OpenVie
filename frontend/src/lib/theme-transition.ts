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
