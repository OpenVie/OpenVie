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

import { useCallback, useEffect, useRef, useState } from "react"
import { useFormatter, useTranslations } from "next-intl"
import toast from "react-hot-toast"
import { AlertCircle, Loader2, Plus, RotateCw, Search, Trash2 } from "lucide-react"
import { useApiClient } from "@/hooks/useApiClient"
import { useAuthStore } from "@/components/providers/StoreProvider"
import { useDebouncedValue } from "@/hooks/useDebouncedValue"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { hidePlaygroundSessionApi, listPlaygroundSessionsApi } from "@/lib/chat-api"
import {
  requestNewChat,
  requestSessionSelect,
  subscribeToActiveChatSession,
  subscribeToChatHistoryChanged,
} from "@/lib/chat-events"
import { useRouter, usePathname } from "@/i18n/navigation"
import { cn } from "@/lib/utils"

import type { PlaygroundSession } from "@/types"

type HistorySnapshot = {
  items: PlaygroundSession[]
  nextCursor: string | null
}

/**
 * Last successfully loaded chat list, keyed by user e-mail. Route changes
 * remount the shell, so the snapshot lets every page draw the list instantly
 * (a background refresh then silently validates it) instead of flashing
 * loading skeletons on every tab switch.
 */
const historySnapshotCache = new Map<string, HistorySnapshot>()

function readHistorySnapshot(userKey: string | undefined): HistorySnapshot | null {
  if (!userKey) return null
  return historySnapshotCache.get(userKey) ?? null
}

function writeHistorySnapshot(userKey: string | undefined, snapshot: HistorySnapshot) {
  if (!userKey) return
  historySnapshotCache.set(userKey, snapshot)
}

/**
 * Sidebar chat history: list, search and hide. Rendered by `AppShell` on every
 * page so the chat list is always visible, and kept in sync with the chat
 * surface through the {@link ./lib/chat-events.ts} events.
 */
export function ChatHistoryPanel() {
  const t = useTranslations("Chat")
  const format = useFormatter()
  const { request } = useApiClient()
  const router = useRouter()
  const pathname = usePathname()
  const userEmail = useAuthStore((state) => state.user?.email)
  // A module-level snapshot may exist from the previous page mount.
  const [cachedSnapshot] = useState(() => readHistorySnapshot(userEmail))
  const historyAbortRef = useRef<AbortController | null>(null)
  const searchAbortRef = useRef<AbortController | null>(null)
  const historyRequestRef = useRef(0)
  const historyNextCursorRef = useRef<string | null>(cachedSnapshot?.nextCursor ?? null)
  const historyLoadingMoreRef = useRef(false)
  const sentinelRef = useRef<HTMLDivElement>(null)
  const activeSessionRef = useRef<string | null>(null)
  const [history, setHistory] = useState<PlaygroundSession[]>(cachedSnapshot?.items ?? [])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyError, setHistoryError] = useState<string | null>(null)
  const [historyLoadingMore, setHistoryLoadingMore] = useState(false)
  const [historyNextCursor, setHistoryNextCursor] = useState<string | null>(cachedSnapshot?.nextCursor ?? null)
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
  const [searchDialogOpen, setSearchDialogOpen] = useState(false)
  const [chatSearch, setChatSearch] = useState("")
  const debouncedChatSearch = useDebouncedValue(chatSearch, 300)
  const [searchResults, setSearchResults] = useState<PlaygroundSession[]>([])
  const [searchLoading, setSearchLoading] = useState(false)
  const [searchError, setSearchError] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<PlaygroundSession | null>(null)
  const [deletingSessionId, setDeletingSessionId] = useState<string | null>(null)

  const loadHistory = useCallback(async ({ append = false }: { append?: boolean } = {}) => {
    if (append && (!historyNextCursorRef.current || historyLoadingMoreRef.current)) return
    const requestId = ++historyRequestRef.current
    if (!append) {
      historyAbortRef.current?.abort()
      historyNextCursorRef.current = null
      setHistoryNextCursor(null)
    }
    const controller = new AbortController()
    historyAbortRef.current = controller
    if (append) {
      historyLoadingMoreRef.current = true
      setHistoryLoadingMore(true)
    }
    else setHistoryLoading(true)
    setHistoryError(null)
    try {
      const result = await listPlaygroundSessionsApi(request, {
        limit: 30,
        cursor: append ? historyNextCursorRef.current : null,
        signal: controller.signal,
      })
      if (requestId !== historyRequestRef.current || controller.signal.aborted) return
      setHistory((current) => {
        const combined = append ? [...current, ...result.items] : result.items
        return Array.from(new Map(combined.map((item) => [item.id, item])).values())
      })
      setHistoryNextCursor(result.nextCursor)
      historyNextCursorRef.current = result.nextCursor
    } catch (error) {
      if (!(error instanceof DOMException && error.name === "AbortError")) setHistoryError(error instanceof Error ? error.message : t("fallback.loadChats"))
    } finally {
      if (requestId === historyRequestRef.current) {
        setHistoryLoading(false)
        setHistoryLoadingMore(false)
        historyLoadingMoreRef.current = false
      }
    }
  }, [request, t])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadHistory(), 0)
    return () => {
      window.clearTimeout(timer)
      historyAbortRef.current?.abort()
    }
  }, [loadHistory])

  useEffect(() => {
    if (!historyNextCursor || historyLoading || historyLoadingMore) return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void loadHistory({ append: true })
    }, { rootMargin: "160px" })
    if (sentinelRef.current) observer.observe(sentinelRef.current)
    return () => observer.disconnect()
  }, [historyLoading, historyLoadingMore, historyNextCursor, loadHistory])

  useEffect(() => subscribeToActiveChatSession((sessionId) => {
    activeSessionRef.current = sessionId
    setActiveSessionId(sessionId)
  }), [])

  useEffect(() => subscribeToChatHistoryChanged(() => {
    void loadHistory()
  }), [loadHistory])

  // Mirror every committed list change into the snapshot cache.
  useEffect(() => {
    writeHistorySnapshot(userEmail, { items: history, nextCursor: historyNextCursor })
  }, [history, historyNextCursor, userEmail])

  useEffect(() => {
    if (!searchDialogOpen) return
    searchAbortRef.current?.abort()
    const controller = new AbortController()
    searchAbortRef.current = controller
    const timer = window.setTimeout(() => {
      setSearchLoading(true)
      setSearchError(null)
      void listPlaygroundSessionsApi(request, {
        limit: 30,
        q: debouncedChatSearch.trim() || undefined,
        signal: controller.signal,
      }).then((result) => {
        if (!controller.signal.aborted) setSearchResults(result.items)
      }).catch((error) => {
        if (!controller.signal.aborted && !(error instanceof DOMException && error.name === "AbortError")) {
          setSearchError(error instanceof Error ? error.message : t("fallback.searchChats"))
        }
      }).finally(() => {
        if (!controller.signal.aborted) setSearchLoading(false)
      })
    }, 0)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [debouncedChatSearch, request, searchDialogOpen, t])

  /** Opens a chat live on the playground, or navigates to it from other pages. */
  function selectSession(sessionId: string) {
    requestSessionSelect(sessionId)
    if (pathname !== "/") router.push("/")
  }

  function startNewChat() {
    requestNewChat()
    if (pathname !== "/") router.push("/")
  }

  function requestDeleteSession(item: PlaygroundSession) {
    if (deletingSessionId) return
    setDeleteTarget(item)
  }

  async function confirmDeleteSession() {
    const item = deleteTarget
    if (!item || deletingSessionId) return
    setDeletingSessionId(item.id)
    try {
      await hidePlaygroundSessionApi(request, item.id)
      const remaining = history.filter((candidate) => candidate.id !== item.id)
      setHistory(remaining)
      setSearchResults((current) => current.filter((candidate) => candidate.id !== item.id))
      setDeleteTarget(null)
      if (activeSessionRef.current === item.id) {
        const next = remaining[0]?.id ?? null
        activeSessionRef.current = next
        setActiveSessionId(next)
        if (next) selectSession(next)
        else requestNewChat()
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("fallback.hideChat"))
    } finally {
      setDeletingSessionId(null)
    }
  }

  return (
    <div>
      <div className="mb-2 flex items-center justify-between px-2">
        <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          {t("chats")}
        </p>
        <button
          type="button"
          onClick={startNewChat}
          className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:opacity-50"
          aria-label={t("startNewChat")}
        >
          <Plus className="size-4" />
        </button>
      </div>
      <button
        type="button"
        onClick={() => setSearchDialogOpen(true)}
        className="mx-1 mb-2 flex w-[calc(100%-0.5rem)] items-center gap-2 rounded-md px-3 py-2 text-left text-sm text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:opacity-50"
      >
        <Search className="size-4" /> {t("searchChats")}
      </button>
      <div className="max-h-[min(48dvh,28rem)] overflow-y-auto">
        {historyLoading && history.length === 0 ? (
          Array.from({ length: 4 }).map((_, index) => (
            <div key={index} className="mx-1 mb-2 h-12 animate-pulse rounded-md bg-muted" />
          ))
        ) : historyError && history.length === 0 ? (
          <div className="px-2 py-3 text-sm text-destructive">
            <p>{historyError}</p>
            <button
              type="button"
              className="mt-2 text-xs font-medium text-foreground underline underline-offset-4"
              onClick={() => void loadHistory()}
            >
              {t("retry")}
            </button>
          </div>
        ) : history.length === 0 ? (
          <p className="px-2 py-3 text-sm text-muted-foreground">{t("noChats")}</p>
        ) : (
          <>{history.map((item) => (
            <div
              key={item.id}
              className={cn(
                "group mb-1 flex items-center rounded-md",
                activeSessionId === item.id ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:bg-muted hover:text-foreground",
              )}
            >
              <button
                type="button"
                onClick={() => selectSession(item.id)}
                className="min-w-0 flex-1 px-3 py-2 text-left"
              >
                <p className="truncate text-sm font-medium">{item.title}</p>
              </button>
              <button
                type="button"
                disabled={Boolean(deletingSessionId)}
                onClick={() => requestDeleteSession(item)}
                className="mr-1 rounded-md p-1.5 text-muted-foreground opacity-70 transition-colors hover:bg-muted hover:text-destructive focus:opacity-100"
                aria-label={t("hideChat", { title: item.title })}
              >
                <Trash2 className="size-3.5" />
              </button>
            </div>
          ))}<div ref={sentinelRef} className="h-1" />{historyLoadingMore && <Loader2 className="mx-auto my-3 size-4 animate-spin text-primary" />}{historyError && <button type="button" className="mx-auto my-3 flex items-center gap-1 text-xs text-destructive" onClick={() => void loadHistory({ append: true })}><RotateCw className="size-3" /> {t("retry")}</button>}{!historyNextCursor && !historyError && <p className="py-3 text-center text-xs text-muted-foreground">{t("endHistory")}</p>}</>
        )}
      </div>

      <Dialog
        open={searchDialogOpen}
        onOpenChange={(open) => {
          setSearchDialogOpen(open)
          if (!open) {
            searchAbortRef.current?.abort()
            setChatSearch("")
            setSearchError(null)
          }
        }}
      >
        <DialogContent className="max-w-xl gap-0 overflow-hidden p-0">
          <DialogHeader className="sr-only">
            <DialogTitle>{t("searchChats")}</DialogTitle>
            <DialogDescription>{t("searchDescription")}</DialogDescription>
          </DialogHeader>
          <div className="relative border-b border-border">
            <Search className="pointer-events-none absolute left-4 top-1/2 size-5 -translate-y-1/2 text-muted-foreground" />
            <input
              autoFocus
              aria-label={t("searchChats")}
              value={chatSearch}
              maxLength={200}
              onChange={(event) => setChatSearch(event.target.value)}
              placeholder={t("searchChats")}
              className="h-14 w-full bg-transparent pl-12 pr-12 text-base text-foreground outline-none placeholder:text-muted-foreground"
            />
            {searchLoading && <Loader2 className="absolute right-4 top-1/2 size-4 -translate-y-1/2 animate-spin text-muted-foreground" />}
          </div>
          <div className="max-h-[min(65dvh,32rem)] min-h-52 overflow-y-auto p-2">
            {searchError ? (
              <div className="grid min-h-48 place-items-center px-5 text-center">
                <div><AlertCircle className="mx-auto mb-2 size-5 text-destructive" /><p className="text-sm text-destructive">{searchError}</p></div>
              </div>
            ) : !searchLoading && searchResults.length === 0 ? (
              <div className="grid min-h-48 place-items-center px-5 text-center">
                <div><Search className="mx-auto mb-3 size-7 text-muted-foreground opacity-50" /><p className="text-sm font-medium text-foreground">{chatSearch.trim() ? t("noChatsFound") : t("noChats")}</p>{chatSearch.trim() && <p className="mt-1 text-xs text-muted-foreground">{t("tryDifferentSearch")}</p>}</div>
              </div>
            ) : (
              <div className="space-y-1">
                {!chatSearch.trim() && <p className="px-3 py-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">{t("recent")}</p>}
                {searchResults.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => {
                      selectSession(item.id)
                      setSearchDialogOpen(false)
                      setChatSearch("")
                    }}
                    className="flex w-full items-center gap-3 rounded-lg px-3 py-3 text-left transition-colors hover:bg-muted focus-visible:bg-muted focus-visible:outline-none"
                  >
                    <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground"><Search className="size-4" /></span>
                    <span className="min-w-0 flex-1"><span className="block truncate text-sm font-medium text-foreground">{item.title}</span><span className="mt-0.5 block text-xs text-muted-foreground">{format.dateTime(new Date(item.last_activity_at), { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })} · {t("messageCount", { count: item.message_count })}</span></span>
                  </button>
                ))}
              </div>
            )}
          </div>
        </DialogContent>
      </Dialog>

      <Dialog
        open={deleteTarget !== null}
        onOpenChange={(open) => {
          if (!open && !deletingSessionId) setDeleteTarget(null)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("deleteTitle")}</DialogTitle>
            <DialogDescription>
              <span className="font-medium text-foreground">{deleteTarget?.title}</span> {t("deleteDescription")}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              disabled={Boolean(deletingSessionId)}
              onClick={() => setDeleteTarget(null)}
            >
              {t("cancel")}
            </Button>
            <Button
              variant="destructive"
              disabled={Boolean(deletingSessionId)}
              onClick={() => void confirmDeleteSession()}
            >
              {deletingSessionId ? (
                <Loader2 className="size-4 animate-spin" />
              ) : (
                <Trash2 className="size-4" />
              )}
              {t("deleteChat")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
