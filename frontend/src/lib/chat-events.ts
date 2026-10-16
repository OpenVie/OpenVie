export const NEW_CHAT_EVENT = "openvie:newchat"

const PENDING_NEW_CHAT_KEY = "openvie.chat.pending-new"

/**
 * Starts a blank chat both when the playground is already mounted and when
 * navigation to it is still in progress.
 */
export function requestNewChat() {
  window.sessionStorage.setItem(PENDING_NEW_CHAT_KEY, "1")
  window.dispatchEvent(new Event(NEW_CHAT_EVENT))
}

export function consumeNewChatRequest(): boolean {
  const pending = window.sessionStorage.getItem(PENDING_NEW_CHAT_KEY) === "1"
  if (pending) window.sessionStorage.removeItem(PENDING_NEW_CHAT_KEY)
  return pending
}
