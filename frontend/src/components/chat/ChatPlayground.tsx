"use client"

import {
  useEffect,
  useCallback,
  useRef,
  useState,
  type ChangeEvent,
  type FormEvent,
  type KeyboardEvent,
} from "react"
import { useFormatter, useTranslations } from "next-intl"
import { useRouter } from "@/i18n/navigation"
import { withNext } from "@/lib/auth-redirect"
import toast from "react-hot-toast"
import {
  ArrowUp,
  FileText,
  Loader2,
  Paperclip,
  Sparkles,
  Square,
  Plus,
  Trash2,
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  RotateCw,
  Search,
} from "lucide-react"
import { AppShell } from "@/components/app/AppShell"
import { AssistantResponse } from "@/components/chat/AssistantResponse"
import { useTokenRehydration } from "@/hooks/useTokenRehydration"
import { useApiClient } from "@/hooks/useApiClient"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { cn } from "@/lib/utils"
import {
  fileTypeFromName,
  getDocumentStatusApi,
  isSupportedDocumentName,
  isTerminalDocumentStatus,
  listDocumentsApi,
  uploadDocumentApi,
  SUPPORTED_DOCUMENT_ACCEPT,
} from "@/lib/documents-api"
import {
  ChatMessageTimeoutError,
  createChatSessionApi,
  getChatMessagesApi,
  submitChatMessageApi,
  listPlaygroundSessionsApi,
  hidePlaygroundSessionApi,
} from "@/lib/chat-api"
import { getTenantWorkspaceApi } from "@/lib/workspace-api"
import type { ChatCitation, Document, DocumentStatus, PlaygroundSession, TenantWorkspace } from "@/types"
import { useDebouncedValue } from "@/hooks/useDebouncedValue"

type SourceStatus = DocumentStatus | "UPLOADING"

type SourceDocument = Omit<Document, "status"> & {
  localId: string
  status: SourceStatus
}

type ChatMessage = {
  id: string
  role: "user" | "assistant"
  content: string
  citations?: ChatCitation[]
  loading?: boolean
  error?: boolean
}

type PersistedPlaygroundState = {
  sessionId: string | null
}

function playgroundStateKey(workspace: TenantWorkspace): string {
  return [
    "openvie.chat.playground",
    workspace.tenantId,
    workspace.knowledgeBase.id,
    workspace.chatbot.id,
  ].join(".")
}

function emptyPlaygroundState(): PersistedPlaygroundState {
  return { sessionId: null }
}

function readPersistedPlaygroundState(key: string): PersistedPlaygroundState {
  if (typeof window === "undefined") {
    return emptyPlaygroundState()
  }

  try {
    const raw = window.sessionStorage.getItem(key)
    if (!raw) return emptyPlaygroundState()

    const restored = JSON.parse(raw) as Partial<PersistedPlaygroundState>
    return {
      sessionId: typeof restored.sessionId === "string" ? restored.sessionId : null,
    }
  } catch {
    window.sessionStorage.removeItem(key)
    return emptyPlaygroundState()
  }
}

function makeId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function sourceFromDocument(document: Document): SourceDocument {
  return {
    ...document,
    localId: document.id,
  }
}

function statusClass(status: SourceStatus): string {
  const classes: Record<SourceStatus, string> = {
    UPLOADING: "bg-slate-100 text-slate-600",
    PENDING: "bg-amber-100 text-amber-800",
    PROCESSING: "bg-blue-100 text-blue-800",
    COMPLETED: "bg-emerald-100 text-emerald-800",
    FAILED: "bg-red-100 text-red-800",
  }
  return classes[status]
}

function isSupportedFile(file: File): boolean {
  return isSupportedDocumentName(file.name)
}

function Playground() {
  const t = useTranslations("Chat")
  const format = useFormatter()
  const { request } = useApiClient()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const canPersistStateRef = useRef(false)
  const activeChatAbortRef = useRef<AbortController | null>(null)
  const historyAbortRef = useRef<AbortController | null>(null)
  const searchAbortRef = useRef<AbortController | null>(null)
  const historyRequestRef = useRef(0)
  const historyNextCursorRef = useRef<string | null>(null)
  const historyLoadingMoreRef = useRef(false)
  const desktopSentinelRef = useRef<HTMLDivElement>(null)
  const mobileSentinelRef = useRef<HTMLDivElement>(null)
  const [workspace, setWorkspace] = useState<TenantWorkspace | null>(null)
  const [message, setMessage] = useState("")
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [sources, setSources] = useState<SourceDocument[]>([])
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const [history, setHistory] = useState<PlaygroundSession[]>([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyError, setHistoryError] = useState<string | null>(null)
  const [historyLoadingMore, setHistoryLoadingMore] = useState(false)
  const [historyNextCursor, setHistoryNextCursor] = useState<string | null>(null)
  const [searchDialogOpen, setSearchDialogOpen] = useState(false)
  const [chatSearch, setChatSearch] = useState("")
  const debouncedChatSearch = useDebouncedValue(chatSearch, 300)
  const [searchResults, setSearchResults] = useState<PlaygroundSession[]>([])
  const [searchLoading, setSearchLoading] = useState(false)
  const [searchError, setSearchError] = useState<string | null>(null)
  const [historyCollapsed, setHistoryCollapsed] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<PlaygroundSession | null>(null)
  const [deletingSessionId, setDeletingSessionId] = useState<string | null>(null)

  function formatBytes(bytes: number): string {
    if (bytes < 1024) return `${format.number(bytes)} B`
    if (bytes < 1024 * 1024) return `${format.number(bytes / 1024, { maximumFractionDigits: 1 })} KB`
    return `${format.number(bytes / (1024 * 1024), { maximumFractionDigits: 1 })} MB`
  }

  const hasCompletedSource = sources.some((source) => source.status === "COMPLETED")
  const hasIndexingSource = sources.some(
    (source) =>
      source.status === "UPLOADING" ||
      source.status === "PENDING" ||
      source.status === "PROCESSING",
  )
  const activeUploads = sources.filter(
    (source) =>
      source.status === "UPLOADING" ||
      source.status === "PENDING" ||
      source.status === "PROCESSING",
  )

  const loadHistory = useCallback(async (preferredSessionId?: string | null, append = false) => {
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
      if (!append) setSessionId((current) => preferredSessionId ?? current ?? result.items[0]?.id ?? null)
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
    canPersistStateRef.current = false

    let cancelled = false
    getTenantWorkspaceApi(request)
      .then((tenantWorkspace) => {
        if (cancelled) return
        const restored = readPersistedPlaygroundState(playgroundStateKey(tenantWorkspace))
        setWorkspace(tenantWorkspace)
        setSessionId(restored.sessionId)
        canPersistStateRef.current = true
      })
      .catch((error) => {
        if (!cancelled) {
          toast.error(error instanceof Error ? error.message : t("fallback.loadWorkspace"))
        }
      })

    return () => {
      cancelled = true
    }
  }, [request, t])

  useEffect(() => {
    if (!workspace) return
    historyNextCursorRef.current = null
    const timer = window.setTimeout(() => void loadHistory(undefined, false), 0)
    return () => {
      window.clearTimeout(timer)
      historyAbortRef.current?.abort()
    }
  }, [workspace, loadHistory])

  useEffect(() => {
    if (!historyNextCursor || historyLoading || historyLoadingMore) return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void loadHistory(undefined, true)
    }, { rootMargin: "160px" })
    if (desktopSentinelRef.current) observer.observe(desktopSentinelRef.current)
    if (mobileSentinelRef.current) observer.observe(mobileSentinelRef.current)
    return () => observer.disconnect()
  }, [historyLoading, historyLoadingMore, historyNextCursor, loadHistory])

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

  useEffect(() => {
    if (!workspace) return

    let cancelled = false
    const loadExistingSources = async () => {
      try {
        const documents = await listDocumentsApi(request, workspace.knowledgeBase.id)
        if (cancelled) return
        setSources((current) => {
          const byId = new Map(current.map((source) => [source.id, source]))
          for (const document of documents) {
            byId.set(document.id, {
              ...sourceFromDocument(document),
              ...byId.get(document.id),
              status: document.status,
              chunkCount: document.chunkCount,
              errorMessage: document.errorMessage,
            })
          }
          return Array.from(byId.values()).sort((a, b) =>
            b.uploadedAt.localeCompare(a.uploadedAt),
          )
        })
      } catch (error) {
        toast.error(error instanceof Error ? error.message : t("fallback.loadDocuments"))
      }
    }

    void loadExistingSources()

    return () => {
      cancelled = true
    }
  }, [request, t, workspace])

  useEffect(() => {
    if (!workspace || !sessionId || sending) return

    let cancelled = false
    getChatMessagesApi(request, sessionId)
      .then((history) => {
        if (cancelled) return
        setMessages(
          history
            .filter((item) => item.role === "user" || item.role === "assistant")
            .map((item) => ({
              id: `${sessionId}-${item.sequence_number ?? makeId()}`,
              role: item.role as "user" | "assistant",
              content: item.content,
              citations: item.citations,
            })),
        )
      })
      .catch((error) => {
        if (cancelled) return
        if (error instanceof Error && error.message === "Chat session was not found.") {
          setSessionId(null)
          return
        }
        toast.error(error instanceof Error ? error.message : t("fallback.loadHistory"))
      })

    return () => {
      cancelled = true
    }
  }, [request, sending, sessionId, t, workspace])

  useEffect(() => {
    return () => {
      activeChatAbortRef.current?.abort()
    }
  }, [])

  useEffect(() => {
    if (!workspace || !canPersistStateRef.current) return
    window.sessionStorage.setItem(
      playgroundStateKey(workspace),
      JSON.stringify({ sessionId } satisfies PersistedPlaygroundState),
    )
  }, [sessionId, workspace])

  useEffect(() => {
    const pollable = sources.filter(
      (source) =>
        source.status !== "UPLOADING" &&
        !isTerminalDocumentStatus(source.status),
    )
    if (pollable.length === 0) return

    let cancelled = false
    const poll = async () => {
      const updates = await Promise.allSettled(
        pollable.map((source) => getDocumentStatusApi(request, source.id)),
      )
      if (cancelled) return
      setSources((current) =>
        current.map((source) => {
          const index = pollable.findIndex((item) => item.id === source.id)
          if (index < 0) return source
          const result = updates[index]
          if (!result || result.status !== "fulfilled") return source
          return {
            ...source,
            status: result.value.status,
            chunkCount: result.value.chunkCount,
            errorMessage: result.value.errorMessage,
          }
        }),
      )
    }

    const timer = window.setInterval(() => {
      void poll()
    }, 1800)
    void poll()

    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [request, sources])

  async function ensureSession(): Promise<string> {
    if (sessionId) return sessionId
    return createSession()
  }

  async function createSession(): Promise<string> {
    if (!workspace) {
      throw new Error(t("workspaceLoading"))
    }
    const session = await createChatSessionApi(request, {
      chatbot_id: workspace.chatbot.id,
      knowledge_base_id: workspace.knowledgeBase.id,
      locale: workspace.chatbot.defaultLocale || workspace.knowledgeBase.defaultLocale,
    })
    setSessionId(session.id)
    return session.id
  }

  function startNewChat() {
    if (sending) return
    setSessionId(null)
    setMessages([])
    setMessage("")
  }

  function switchSession(nextSessionId: string) {
    if (sending || nextSessionId === sessionId) return
    setMessages([])
    setSessionId(nextSessionId)
  }

  function requestDeleteSession(item: PlaygroundSession) {
    if (sending || deletingSessionId) return
    setDeleteTarget(item)
  }

  async function confirmDeleteSession() {
    const item = deleteTarget
    if (!item || sending || deletingSessionId) return
    setDeletingSessionId(item.id)
    try {
      await hidePlaygroundSessionApi(request, item.id)
      const remaining = history.filter((candidate) => candidate.id !== item.id)
      setHistory(remaining)
      setSearchResults((current) => current.filter((candidate) => candidate.id !== item.id))
      setDeleteTarget(null)
      if (sessionId === item.id) {
        setMessages([])
        setSessionId(remaining[0]?.id ?? null)
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("fallback.hideChat"))
    } finally {
      setDeletingSessionId(null)
    }
  }

  async function submitMessage(event?: FormEvent) {
    event?.preventDefault()
    const content = message.trim()
    if (!content || sending) return
    if (!hasCompletedSource) {
      toast.error(
        hasIndexingSource
          ? t("waitForIndexing")
          : t("uploadBeforeQuestion"),
      )
      return
    }

    const assistantId = makeId()
    setMessages((current) => [
      ...current,
      { id: makeId(), role: "user", content },
      { id: assistantId, role: "assistant", content: t("thinking"), loading: true },
    ])
    setMessage("")
    setSending(true)

    const abortController = new AbortController()
    activeChatAbortRef.current = abortController

    try {
      const activeSessionId = await ensureSession()
      let response
      try {
        response = await submitChatMessageApi(
          request,
          activeSessionId,
          content,
          abortController.signal,
        )
      } catch (error) {
        if (error instanceof Error && error.message === "Chat session was not found.") {
          setSessionId(null)
          const replacementSessionId = await createSession()
          response = await submitChatMessageApi(
            request,
            replacementSessionId,
            content,
            abortController.signal,
          )
        } else {
          throw error
        }
      }
      setMessages((current) => {
        const assistantMessage: ChatMessage = {
          id: assistantId,
          role: "assistant",
          content: response.content.trim() || t("fallback.noAnswer"),
          citations: response.citations,
          error: response.content.trim().length === 0,
        }
        let replaced = false
        const updated = current.map((item) => {
          if (item.id !== assistantId) return item
          replaced = true
          return assistantMessage
        })
        return replaced ? updated : [...updated, assistantMessage]
      })
      await loadHistory(activeSessionId)
    } catch (error) {
      const aborted = abortController.signal.aborted
      const errorMessage = aborted
        ? t("canceled")
        : error instanceof ChatMessageTimeoutError
          ? t("fallback.timeout")
          : error instanceof Error
          ? error.message
          : t("fallback.answer")
      setMessages((current) =>
        current.map((item) =>
          item.id === assistantId
            ? {
                id: assistantId,
                role: "assistant",
                content: errorMessage,
                error: !aborted,
              }
            : item,
        ),
      )
    } finally {
      if (activeChatAbortRef.current === abortController) {
        activeChatAbortRef.current = null
      }
      setSending(false)
    }
  }

  function cancelMessage() {
    activeChatAbortRef.current?.abort()
  }

  function handleComposerKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault()
      void submitMessage()
    }
  }

  async function uploadFiles(files: File[]) {
    if (!workspace) {
      toast.error(t("workspaceLoading"))
      return
    }

    for (const file of files) {
      if (!isSupportedFile(file)) {
        toast.error(`${file.name} is not supported. Use PDF, DOCX, TXT, Markdown, HTML, XLSX, or CSV.`)
        continue
      }

      const localId = makeId()
      const pendingSource: SourceDocument = {
        id: localId,
        localId,
        fileName: file.name,
        fileType: fileTypeFromName(file.name),
        fileSizeBytes: file.size,
        jobId: localId,
        knowledgeBaseId: workspace.knowledgeBase.id,
        status: "UPLOADING",
        uploadedAt: new Date().toISOString(),
      }
      setSources((current) => [...current, pendingSource])

      try {
        const uploaded = await uploadDocumentApi(request, file, workspace.knowledgeBase.id)
        setSources((current) =>
          current.map((source) =>
            source.localId === localId
              ? {
                  ...source,
                  id: uploaded.id,
                  jobId: uploaded.jobId,
                  fileName: uploaded.fileName,
                  status: uploaded.status,
                }
              : source,
          ),
        )
      } catch (error) {
        const errorMessage =
          error instanceof Error ? error.message : t("fallback.upload")
        setSources((current) =>
          current.map((source) =>
            source.localId === localId
              ? { ...source, status: "FAILED", errorMessage }
              : source,
          ),
        )
        toast.error(errorMessage)
      }
    }
  }

  function handleFiles(event: ChangeEvent<HTMLInputElement>) {
    const selectedFiles = Array.from(event.target.files ?? [])
    if (selectedFiles.length > 0) {
      void uploadFiles(selectedFiles)
    }
    event.target.value = ""
  }

  const sendDisabled = !workspace || !message.trim() || !hasCompletedSource || sending

  const historyPanel = (
    <div className="flex h-full flex-col bg-slate-50">
      <div className="space-y-2 border-b border-slate-200 p-3">
        <Button className="w-full justify-start gap-2" variant="outline" disabled={sending} onClick={startNewChat}>
          <Plus className="size-4" /> {t("newChat")}
        </Button>
        <Button className="w-full justify-start gap-2 text-slate-600" variant="ghost" disabled={sending} onClick={() => setSearchDialogOpen(true)}>
          <Search className="size-4" /> {t("searchChats")}
        </Button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-2">
        {historyLoading ? Array.from({ length: 5 }).map((_, index) => <div key={index} className="mb-2 h-14 animate-pulse rounded-lg bg-slate-200" />) : historyError && history.length === 0 ? (
          <div className="p-3 text-sm text-red-700"><AlertCircle className="mb-2 size-5" /><p>{historyError}</p><Button className="mt-3" size="sm" variant="outline" onClick={() => void loadHistory()}>{t("retry")}</Button></div>
        ) : history.length === 0 ? (
          <p className="p-4 text-center text-sm text-slate-500">{t("historyWillAppear")}</p>
        ) : <>{history.map((item) => (
          <div key={item.id} className={cn("group mb-1 flex items-start rounded-lg", sessionId === item.id ? "bg-indigo-100 text-indigo-950" : "hover:bg-slate-200")}>
            <button type="button" disabled={sending} onClick={() => switchSession(item.id)} className="min-w-0 flex-1 px-3 py-2 text-left disabled:cursor-not-allowed">
              <p className="truncate text-sm font-medium">{item.title}</p>
              <p className="mt-0.5 text-xs text-slate-500">{format.dateTime(new Date(item.last_activity_at), { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })}</p>
            </button>
            <button type="button" disabled={sending || Boolean(deletingSessionId)} onClick={() => requestDeleteSession(item)} className="m-1 rounded-md p-2 text-slate-400 opacity-0 hover:bg-white hover:text-red-600 group-hover:opacity-100 focus:opacity-100" aria-label={t("hideChat", { title: item.title })}><Trash2 className="size-4" /></button>
          </div>
        ))}<div ref={desktopSentinelRef} className="h-1" />{historyLoadingMore && <Loader2 className="mx-auto my-3 size-4 animate-spin text-indigo-600" />}{historyError && <button type="button" className="mx-auto my-3 flex items-center gap-1 text-xs text-red-600" onClick={() => void loadHistory(undefined, true)}><RotateCw className="size-3" /> {t("retryOlder")}</button>}{!historyNextCursor && !historyError && <p className="py-3 text-center text-xs text-slate-400">{t("endHistory")}</p>}</>}
      </div>
    </div>
  )

  const mobileHistoryPanel = (
    <div>
      <div className="mb-2 flex items-center justify-between px-2">
        <p className="text-xs font-semibold uppercase tracking-wider text-slate-400">
          {t("chats")}
        </p>
        <button
          type="button"
          disabled={sending}
          onClick={startNewChat}
          className="rounded-md p-1.5 text-slate-300 transition-colors hover:bg-slate-800 hover:text-white disabled:opacity-50"
          aria-label={t("startNewChat")}
        >
          <Plus className="size-4" />
        </button>
      </div>
      <button
        type="button"
        disabled={sending}
        onClick={() => setSearchDialogOpen(true)}
        className="mx-1 mb-2 flex w-[calc(100%-0.5rem)] items-center gap-2 rounded-md px-3 py-2 text-left text-sm text-slate-300 transition-colors hover:bg-slate-800 hover:text-white disabled:opacity-50"
      >
        <Search className="size-4" /> {t("searchChats")}
      </button>
      <div className="max-h-[min(48dvh,28rem)] overflow-y-auto">
        {historyLoading ? (
          Array.from({ length: 4 }).map((_, index) => (
            <div key={index} className="mx-1 mb-2 h-12 animate-pulse rounded-md bg-slate-800" />
          ))
        ) : historyError && history.length === 0 ? (
          <div className="px-2 py-3 text-sm text-red-300">
            <p>{historyError}</p>
            <button
              type="button"
              className="mt-2 text-xs font-medium text-white underline underline-offset-4"
              onClick={() => void loadHistory()}
            >
              {t("retry")}
            </button>
          </div>
        ) : history.length === 0 ? (
          <p className="px-2 py-3 text-sm text-slate-400">{t("noChats")}</p>
        ) : (
          <>{history.map((item) => (
            <div
              key={item.id}
              className={cn(
                "group mb-1 flex items-center rounded-md",
                sessionId === item.id ? "bg-indigo-600 text-white" : "text-slate-300 hover:bg-slate-800 hover:text-white",
              )}
            >
              <button
                type="button"
                disabled={sending}
                onClick={() => switchSession(item.id)}
                className="min-w-0 flex-1 px-3 py-2 text-left disabled:opacity-50"
              >
                <p className="truncate text-sm font-medium">{item.title}</p>
              </button>
              <button
                type="button"
                disabled={sending || Boolean(deletingSessionId)}
                onClick={() => requestDeleteSession(item)}
                className="mr-1 rounded-md p-1.5 text-slate-400 opacity-70 transition-colors hover:bg-slate-700 hover:text-red-300 focus:opacity-100"
                aria-label={t("hideChat", { title: item.title })}
              >
                <Trash2 className="size-3.5" />
              </button>
            </div>
          ))}<div ref={mobileSentinelRef} className="h-1" />{historyLoadingMore && <Loader2 className="mx-auto my-3 size-4 animate-spin text-indigo-300" />}{historyError && <button type="button" className="mx-auto my-3 flex items-center gap-1 text-xs text-red-300" onClick={() => void loadHistory(undefined, true)}><RotateCw className="size-3" /> {t("retry")}</button>}{!historyNextCursor && !historyError && <p className="py-3 text-center text-xs text-slate-500">{t("endHistory")}</p>}</>
        )}
      </div>
    </div>
  )

  const playgroundContent = (
    <div
      className="flex h-[calc(100dvh-3.5rem)] min-h-[32rem] flex-col bg-white"
    >
      <div className="flex min-h-0 flex-1">
        <aside
            className={cn(
              "relative hidden shrink-0 border-r border-slate-200 bg-slate-50 transition-[width] duration-300 ease-in-out xl:block",
              historyCollapsed ? "w-12" : "w-72",
            )}
          >
            <div className="h-full overflow-hidden">
              <div
                className={cn(
                  "h-full w-72 transition-all duration-300 ease-in-out",
                  historyCollapsed
                    ? "pointer-events-none -translate-x-3 opacity-0"
                    : "translate-x-0 opacity-100",
                )}
              >
                {historyPanel}
              </div>
            </div>
            <button
              type="button"
              onClick={() => setHistoryCollapsed((collapsed) => !collapsed)}
              className="absolute -right-3 top-4 z-10 grid size-7 place-items-center rounded-full border border-slate-200 bg-white text-slate-500 shadow-sm transition-all duration-200 hover:scale-105 hover:border-indigo-300 hover:bg-indigo-50 hover:text-indigo-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300"
              aria-label={historyCollapsed ? t("expandHistory") : t("collapseHistory")}
              aria-expanded={!historyCollapsed}
            >
              {historyCollapsed ? (
                <ChevronRight className="size-4" />
              ) : (
                <ChevronLeft className="size-4" />
              )}
            </button>
          </aside>
        <div className="flex min-w-0 flex-1 flex-col">

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto flex min-h-full w-full max-w-3xl flex-col px-4 py-8 sm:px-6">
          {messages.length === 0 ? (
            <div className="m-auto flex max-w-lg flex-col items-center py-12 text-center">
              <div className="mb-5 grid size-14 place-items-center rounded-2xl bg-indigo-50 text-indigo-600">
                <Sparkles className="size-7" />
              </div>
              <h2 className="text-2xl font-semibold tracking-tight text-slate-900">
                {t("emptyTitle")}
              </h2>
              <p className="mt-2 text-sm leading-6 text-slate-500 sm:text-base">
                {t("emptyDescription")}
              </p>
            </div>
          ) : (
            <div className="flex flex-1 flex-col justify-end gap-5 py-4">
              {messages.map((item) => (
                <div
                  key={item.id}
                  className={cn("flex", item.role === "user" ? "justify-end" : "justify-start")}
                >
                  {item.role === "user" ? (
                    <div className="max-w-[85%] whitespace-pre-wrap break-words rounded-2xl rounded-br-md bg-indigo-600 px-4 py-3 text-sm leading-6 text-white sm:max-w-[75%]">
                      {item.content}
                    </div>
                  ) : item.loading ? (
                    <div className="w-full py-2 text-sm text-slate-500">
                      <span className="inline-flex items-center gap-2">
                        <Loader2 className="size-3.5 animate-spin" />
                        {item.content}
                      </span>
                    </div>
                  ) : (
                    <div className="w-full py-1">
                      <AssistantResponse
                        content={item.content}
                        citations={item.citations}
                        error={item.error}
                      />
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className="shrink-0 border-t border-slate-100 bg-white px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-3 sm:px-6 sm:pb-5">
        <form onSubmit={submitMessage} className="mx-auto w-full max-w-3xl">
          {activeUploads.length > 0 && (
            <div className="mb-2 flex gap-2 overflow-x-auto pb-1">
              {activeUploads.map((source) => (
                <div
                  key={source.localId}
                  className="flex min-w-0 max-w-72 shrink-0 items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 py-2 pl-2.5 pr-1.5"
                  title={source.errorMessage ?? source.fileName}
                >
                  <div className="grid size-8 shrink-0 place-items-center rounded-md bg-white text-indigo-600 shadow-sm">
                    {source.status === "UPLOADING" || source.status === "PROCESSING" ? (
                      <Loader2 className="size-4 animate-spin" />
                    ) : (
                      <FileText className="size-4" />
                    )}
                  </div>
                  <div className="min-w-0">
                    <p className="truncate text-xs font-medium text-slate-800">
                      {source.fileName}
                    </p>
                    <p className="truncate text-[11px] text-slate-500">
                      {formatBytes(source.fileSizeBytes)}
                      {source.status === "COMPLETED" ? ` · ${t("readyToAnswer")}` : ""}
                    </p>
                    <span
                      className={cn(
                        "mt-1 inline-flex rounded-full px-2 py-0.5 text-[10px] font-medium",
                        statusClass(source.status),
                      )}
                    >
                      {t(`status.${source.status}`)}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}

          <div className="relative rounded-2xl border border-slate-300 bg-white p-2 shadow-[0_8px_30px_rgba(15,23,42,0.08)] focus-within:border-indigo-400 focus-within:ring-2 focus-within:ring-indigo-100">
            <textarea
              value={message}
              onChange={(event) => setMessage(event.target.value)}
              onKeyDown={handleComposerKeyDown}
              rows={2}
              placeholder={
                hasCompletedSource
                  ? t("questionPlaceholder")
                  : t("uploadPlaceholder")
              }
              className="block max-h-36 min-h-14 w-full resize-none bg-transparent px-2 py-1.5 text-sm leading-6 text-slate-900 outline-none placeholder:text-slate-400"
              aria-label={t("message")}
            />
            <div className="flex h-9 items-center justify-between gap-2">
              <div className="relative">
                <button
                  type="button"
                  disabled={sending}
                  onClick={() => fileInputRef.current?.click()}
                  className={cn(
                    "flex h-9 items-center gap-1.5 rounded-lg px-2.5 text-sm text-slate-600 hover:bg-slate-100 hover:text-slate-900",
                    sending && "cursor-not-allowed opacity-50",
                  )}
                >
                  <Paperclip className="size-4" />
                  <span>{t("addSource")}</span>
                </button>
                <input
                  ref={fileInputRef}
                  type="file"
                  multiple
                  accept={SUPPORTED_DOCUMENT_ACCEPT}
                  className="hidden"
                  onChange={handleFiles}
                />
              </div>
              {sending && (
                <button
                  type="button"
                  onClick={cancelMessage}
                  className="h-9 rounded-lg px-3 text-sm font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-900 hidden"
                >
                  {t("cancel")}
                </button>
              )}
              <button
                type={sending ? "button" : "submit"}
                disabled={!sending && sendDisabled}
                onClick={sending ? cancelMessage : undefined}
                className={cn(
                  "grid size-9 shrink-0 place-items-center rounded-lg text-white transition-colors",
                  sending
                    ? "bg-slate-900 hover:bg-slate-700"
                    : "bg-indigo-600 hover:bg-indigo-700",
                  !sending && sendDisabled &&
                    "cursor-not-allowed bg-slate-200 text-slate-400 hover:bg-slate-200",
                )}
                aria-label={sending ? t("cancelResponse") : t("sendMessage")}
              >
                {sending ? <Square className="size-3.5 fill-current" /> : <ArrowUp className="size-4" />}
              </button>
            </div>
          </div>
          <p className="mt-2 text-center text-[11px] text-slate-400">
            {t("uploadNotice")}
          </p>
        </form>
      </div>
        </div>
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
          <div className="relative border-b border-slate-200">
            <Search className="pointer-events-none absolute left-4 top-1/2 size-5 -translate-y-1/2 text-slate-400" />
            <input
              autoFocus
              aria-label={t("searchChats")}
              value={chatSearch}
              maxLength={200}
              onChange={(event) => setChatSearch(event.target.value)}
              placeholder={t("searchChats")}
              className="h-14 w-full bg-white pl-12 pr-12 text-base text-slate-900 outline-none placeholder:text-slate-400"
            />
            {searchLoading && <Loader2 className="absolute right-4 top-1/2 size-4 -translate-y-1/2 animate-spin text-slate-400" />}
          </div>
          <div className="max-h-[min(65dvh,32rem)] min-h-52 overflow-y-auto p-2">
            {searchError ? (
              <div className="grid min-h-48 place-items-center px-5 text-center">
                <div><AlertCircle className="mx-auto mb-2 size-5 text-red-500" /><p className="text-sm text-red-700">{searchError}</p></div>
              </div>
            ) : !searchLoading && searchResults.length === 0 ? (
              <div className="grid min-h-48 place-items-center px-5 text-center">
                <div><Search className="mx-auto mb-3 size-7 text-slate-300" /><p className="text-sm font-medium text-slate-600">{chatSearch.trim() ? t("noChatsFound") : t("noChats")}</p>{chatSearch.trim() && <p className="mt-1 text-xs text-slate-400">{t("tryDifferentSearch")}</p>}</div>
              </div>
            ) : (
              <div className="space-y-1">
                {!chatSearch.trim() && <p className="px-3 py-2 text-xs font-semibold uppercase tracking-wider text-slate-400">{t("recent")}</p>}
                {searchResults.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => {
                      switchSession(item.id)
                      setSearchDialogOpen(false)
                      setChatSearch("")
                    }}
                    className="flex w-full items-center gap-3 rounded-lg px-3 py-3 text-left transition-colors hover:bg-slate-100 focus-visible:bg-slate-100 focus-visible:outline-none"
                  >
                    <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-slate-100 text-slate-500"><Search className="size-4" /></span>
                    <span className="min-w-0 flex-1"><span className="block truncate text-sm font-medium text-slate-800">{item.title}</span><span className="mt-0.5 block text-xs text-slate-400">{format.dateTime(new Date(item.last_activity_at), { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })} · {t("messageCount", { count: item.message_count })}</span></span>
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
              <span className="font-medium text-slate-700">{deleteTarget?.title}</span> {t("deleteDescription")}
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

  return (
    <AppShell contentClassName="p-0" mobileNavContent={mobileHistoryPanel}>
      {playgroundContent}
    </AppShell>
  )
}

export default function ChatPlayground() {
  const t = useTranslations("Chat")
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
      <div className="grid min-h-dvh place-items-center bg-white">
        <Loader2 className="size-8 animate-spin text-indigo-600" aria-label={t("loading")} />
      </div>
    )
  }

  return <Playground />
}
