export const NEW_CHAT_EVENT = "openvie:newchat"

const PENDING_NEW_CHAT_KEY = "openvie.chat.pending-new"
const PENDING_SESSION_SELECT_KEY = "openvie.chat.pending-session"
const SESSION_SELECT_EVENT = "openvie:chat-session-select"
const CHAT_HISTORY_CHANGED_EVENT = "openvie:chat-history-changed"
const ACTIVE_CHAT_SESSION_EVENT = "openvie:chat-active-session"

/**
 * Starts a blank chat both when the playground is already mounted and when
 * navigation to it is still in progress.
 */
export function requestNewChat() {
  window.sessionStorage.removeItem(PENDING_SESSION_SELECT_KEY)
  window.sessionStorage.setItem(PENDING_NEW_CHAT_KEY, "1")
  window.dispatchEvent(new Event(NEW_CHAT_EVENT))
}

export function consumeNewChatRequest(): boolean {
  const pending = window.sessionStorage.getItem(PENDING_NEW_CHAT_KEY) === "1"
  if (pending) window.sessionStorage.removeItem(PENDING_NEW_CHAT_KEY)
  return pending
}

/**
 * Opens a chat from anywhere: while the playground is mounted it switches
 * live; from other pages the selection is stashed and consumed on arrival.
 */
export function requestSessionSelect(sessionId: string) {
  window.sessionStorage.setItem(PENDING_SESSION_SELECT_KEY, sessionId)
  window.dispatchEvent(new CustomEvent(SESSION_SELECT_EVENT, { detail: sessionId }))
}

export function consumePendingSessionSelect(): string | null {
  const pending = window.sessionStorage.getItem(PENDING_SESSION_SELECT_KEY)
  if (pending) window.sessionStorage.removeItem(PENDING_SESSION_SELECT_KEY)
  return pending
}

/** The playground tells the sidebar its open session (null = none/blank). */
export function notifyActiveChatSession(sessionId: string | null) {
  window.dispatchEvent(new CustomEvent(ACTIVE_CHAT_SESSION_EVENT, { detail: sessionId }))
}

/** Nudges the sidebar chat list to reload (e.g. after a message was sent). */
export function notifyChatHistoryChanged() {
  window.dispatchEvent(new Event(CHAT_HISTORY_CHANGED_EVENT))
}

export function subscribeToSessionSelect(handler: (sessionId: string | null) => void) {
  const listener = (event: Event) => handler((event as CustomEvent<string | null>).detail)
  window.addEventListener(SESSION_SELECT_EVENT, listener)
  return () => window.removeEventListener(SESSION_SELECT_EVENT, listener)
}

export function subscribeToActiveChatSession(handler: (sessionId: string | null) => void) {
  const listener = (event: Event) => handler((event as CustomEvent<string | null>).detail)
  window.addEventListener(ACTIVE_CHAT_SESSION_EVENT, listener)
  return () => window.removeEventListener(ACTIVE_CHAT_SESSION_EVENT, listener)
}

export function subscribeToChatHistoryChanged(handler: () => void) {
  window.addEventListener(CHAT_HISTORY_CHANGED_EVENT, handler)
  return () => window.removeEventListener(CHAT_HISTORY_CHANGED_EVENT, handler)
}
