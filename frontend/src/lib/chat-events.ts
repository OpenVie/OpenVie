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

export const NEW_CHAT_EVENT = "openvie:newchat"

const PENDING_NEW_CHAT_KEY = "openvie.chat.pending-new"
const PENDING_SESSION_SELECT_KEY = "openvie.chat.pending-session"
const PENDING_DOCUMENT_SCOPE_KEY = "openvie.chat.pending-document-scope"
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

/**
 * Opens the chat scoped to one or more indexed documents (currently built for
 * the document viewer's "chat with this document" affordance). The playground
 * stashes the ids, validates each indexed status, then answers every message
 * inside this scope until the user clears it.
 */
export function requestChatWithDocumentScope(documentId: string, ...extraDocumentIds: string[]) {
  window.sessionStorage.setItem(
    PENDING_DOCUMENT_SCOPE_KEY,
    JSON.stringify([documentId, ...extraDocumentIds]),
  )
}

export function consumePendingDocumentScope(): string[] | null {
  try {
    const raw = window.sessionStorage.getItem(PENDING_DOCUMENT_SCOPE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return null
    const ids = parsed.filter((id): id is string => typeof id === "string")
    return ids.length > 0 ? ids : null
  } catch {
    return null
  } finally {
    window.sessionStorage.removeItem(PENDING_DOCUMENT_SCOPE_KEY)
  }
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
