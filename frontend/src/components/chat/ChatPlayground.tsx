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
  Upload,
  Square,
  AlertCircle,
} from "lucide-react"
import { AppShell } from "@/components/app/AppShell"
import { AssistantResponse } from "@/components/chat/AssistantResponse"
import { useTokenRehydration } from "@/hooks/useTokenRehydration"
import { useApiClient } from "@/hooks/useApiClient"
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
} from "@/lib/chat-api"
import { getTenantWorkspaceApi } from "@/lib/workspace-api"
import type { ChatCitation, Document, DocumentStatus, TenantWorkspace } from "@/types"
import {
  consumeNewChatRequest,
  consumePendingSessionSelect,
  NEW_CHAT_EVENT,
  notifyActiveChatSession,
  notifyChatHistoryChanged,
  subscribeToSessionSelect,
} from "@/lib/chat-events"

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
    UPLOADING: "ov-source-status-uploading",
    PENDING: "ov-source-status-pending",
    PROCESSING: "ov-source-status-processing",
    COMPLETED: "ov-source-status-completed",
    FAILED: "ov-source-status-failed",
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
  const chatScrollRef = useRef<HTMLDivElement>(null)
  const followLatestRef = useRef(true)
  const [workspace, setWorkspace] = useState<TenantWorkspace | null>(null)
  const [message, setMessage] = useState("")
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [sources, setSources] = useState<SourceDocument[]>([])
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const [heroAnimationKey, setHeroAnimationKey] = useState(0)
  const [recentUploadIds, setRecentUploadIds] = useState<string[]>([])

  useEffect(() => {
    const replayHeroAnimation = () => setHeroAnimationKey((current) => current + 1)
    window.addEventListener("openvie:themechange", replayHeroAnimation)
    return () => window.removeEventListener("openvie:themechange", replayHeroAnimation)
  }, [])

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
  const recentUploads = sources.filter((source) => recentUploadIds.includes(source.localId))
  const sourceProcessList = recentUploads.length > 0 ? (
    <div className="ov-source-process-list" aria-live="polite">
      {recentUploads.map((source) => {
        const inProgress = source.status === "UPLOADING" || source.status === "PENDING" || source.status === "PROCESSING"
        return (
          <div key={source.localId} className="ov-source-process-card" title={source.errorMessage ?? source.fileName}>
            <div className="ov-source-process-icon">
              {inProgress ? <Loader2 className="size-5 animate-spin" /> : source.status === "FAILED" ? <AlertCircle className="size-5" /> : <FileText className="size-5" />}
            </div>
            <div className="min-w-0 flex-1">
              <div className="flex items-center justify-between gap-3">
                <p className="truncate text-sm font-bold text-foreground">{source.fileName}</p>
                <span className={cn("ov-source-process-status", statusClass(source.status))}>
                  <i aria-hidden="true" />{t(`status.${source.status}`)}
                </span>
              </div>
              <p className="mt-0.5 truncate text-xs text-muted-foreground">
                {formatBytes(source.fileSizeBytes)}
                {source.status === "COMPLETED" ? ` · ${t("readyToAnswer")}` : source.status === "FAILED" && source.errorMessage ? ` · ${source.errorMessage}` : ""}
              </p>
              <div className="ov-source-process-track" aria-hidden="true">
                <span className={cn("ov-source-process-progress", inProgress && "ov-source-process-progress-active", source.status === "COMPLETED" && "ov-source-process-progress-ready", source.status === "FAILED" && "ov-source-process-progress-failed")} />
              </div>
            </div>
          </div>
        )
      })}
    </div>
  ) : null

  useEffect(() => {
    canPersistStateRef.current = false

    let cancelled = false
    getTenantWorkspaceApi(request)
      .then((tenantWorkspace) => {
        if (cancelled) return
        const restored = readPersistedPlaygroundState(playgroundStateKey(tenantWorkspace))
        setWorkspace(tenantWorkspace)
        const nextSessionId = consumeNewChatRequest()
          ? null
          : consumePendingSessionSelect() ?? restored.sessionId
        setSessionId(nextSessionId)
        canPersistStateRef.current = true
        if (!nextSessionId) {
          // No chat was requested explicitly: continue the most recent one.
          listPlaygroundSessionsApi(request, { limit: 1 })
            .then((result) => {
              if (!cancelled) setSessionId((current) => current ?? result.items[0]?.id ?? null)
            })
            .catch(() => {
              // The sidebar shows its own reload affordance; start blank instead.
            })
        }
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
      const controller = activeChatAbortRef.current
      activeChatAbortRef.current = null
      controller?.abort()
    }
  }, [])

  useEffect(() => {
    if (followLatestRef.current && chatScrollRef.current) {
      chatScrollRef.current.scrollTop = chatScrollRef.current.scrollHeight
    }
  }, [messages])

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

  async function ensureSession(signal: AbortSignal): Promise<string> {
    if (sessionId) return sessionId
    return createSession(signal)
  }

  async function createSession(signal: AbortSignal): Promise<string> {
    if (!workspace) {
      throw new Error(t("workspaceLoading"))
    }
    const session = await createChatSessionApi(request, {
      chatbot_id: workspace.chatbot.id,
      knowledge_base_id: workspace.knowledgeBase.id,
      locale: workspace.chatbot.defaultLocale || workspace.knowledgeBase.defaultLocale,
    })
    signal.throwIfAborted()
    setSessionId(session.id)
    return session.id
  }

  const cancelMessage = useCallback(() => {
    const controller = activeChatAbortRef.current
    if (!controller) return
    activeChatAbortRef.current = null
    controller.abort()
    setMessages((current) => current.map((item): ChatMessage =>
      item.loading ? { id: item.id, role: "assistant", content: t("canceled") } : item,
    ))
    setSending(false)
  }, [t])

  const startNewChat = useCallback(() => {
    cancelMessage()
    followLatestRef.current = true
    setSessionId(null)
    setMessages([])
    setMessage("")
  }, [cancelMessage])

  useEffect(() => {
    const onNewChat = () => {
      consumeNewChatRequest()
      startNewChat()
    }
    window.addEventListener(NEW_CHAT_EVENT, onNewChat)
    return () => window.removeEventListener(NEW_CHAT_EVENT, onNewChat)
  }, [startNewChat])

  const switchSession = useCallback((nextSessionId: string) => {
    if (nextSessionId === sessionId) return
    cancelMessage()
    followLatestRef.current = true
    setMessages([])
    setSessionId(nextSessionId)
  }, [cancelMessage, sessionId])

  useEffect(() => {
    const onSessionSelect = (nextSessionId: string | null) => {
      if (nextSessionId === null) {
        // The open chat was hidden and nothing remained in the history.
        cancelMessage()
        followLatestRef.current = true
        setMessages([])
        setSessionId(null)
        return
      }
      switchSession(nextSessionId)
    }
    return subscribeToSessionSelect(onSessionSelect)
  }, [cancelMessage, switchSession])

  // Keep the sidebar chat list in step with the conversation being viewed.
  useEffect(() => {
    notifyActiveChatSession(sessionId)
    return () => notifyActiveChatSession(null)
  }, [sessionId])

  async function submitMessage(event?: FormEvent) {
    event?.preventDefault()
    const content = message.trim()
    if (!content || sending || activeChatAbortRef.current) return
    if (!hasCompletedSource) {
      toast.error(
        hasIndexingSource
          ? t("waitForIndexing")
          : t("uploadBeforeQuestion"),
      )
      return
    }

    const assistantId = makeId()
    followLatestRef.current = true
    setMessages((current) => [
      ...current,
      { id: makeId(), role: "user", content },
      { id: assistantId, role: "assistant", content: "", loading: true },
    ])
    setMessage("")
    setSending(true)

    const abortController = new AbortController()
    activeChatAbortRef.current = abortController
    let historyRefreshed = false
    const isActive = () =>
      activeChatAbortRef.current === abortController && !abortController.signal.aborted
    const streamOptions = {
      signal: abortController.signal,
      onContent: (preview: string) => {
        if (!isActive()) return
        if (!historyRefreshed) {
          historyRefreshed = true
          notifyChatHistoryChanged()
        }
        setMessages((current) => current.map((item) =>
          item.id === assistantId ? { ...item, content: preview, citations: undefined } : item,
        ))
      },
      onReset: () => {
        if (!isActive()) return
        setMessages((current) => current.map((item) =>
          item.id === assistantId ? { ...item, content: "", citations: undefined } : item,
        ))
      },
    }

    try {
      let activeSessionId = await ensureSession(abortController.signal)
      if (!isActive()) return
      let response
      try {
        response = await submitChatMessageApi(request, activeSessionId, content, streamOptions)
      } catch (error) {
        if (!isActive()) return
        if (error instanceof Error && error.message === "Chat session was not found.") {
          setSessionId(null)
          activeSessionId = await createSession(abortController.signal)
          if (!isActive()) return
          streamOptions.onReset()
          response = await submitChatMessageApi(request, activeSessionId, content, streamOptions)
        } else {
          throw error
        }
      }
      if (!isActive()) return
      setMessages((current) => current.map((item): ChatMessage =>
        item.id === assistantId
          ? { id: assistantId, role: "assistant", content: response.content, citations: response.citations }
          : item,
      ))
      notifyChatHistoryChanged()
    } catch (error) {
      if (!isActive()) return
      const errorMessage = error instanceof ChatMessageTimeoutError
        ? t("fallback.timeout")
        : error instanceof Error
          ? error.message
          : t("fallback.answer")
      setMessages((current) => current.map((item): ChatMessage =>
        item.id === assistantId
          ? { id: assistantId, role: "assistant", content: errorMessage, error: true }
          : item,
      ))
    } finally {
      if (activeChatAbortRef.current === abortController) {
        activeChatAbortRef.current = null
        setSending(false)
      }
    }
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

    await Promise.all(files.map(async (file) => {
      if (!isSupportedFile(file)) {
        toast.error(`${file.name} is not supported. Use PDF, DOCX, TXT, Markdown, HTML, XLSX, or CSV.`)
        return
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
      setRecentUploadIds((current) => [...current, localId])

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
    }))
  }

  function handleFiles(event: ChangeEvent<HTMLInputElement>) {
    const selectedFiles = Array.from(event.target.files ?? [])
    if (selectedFiles.length > 0) {
      void uploadFiles(selectedFiles)
    }
    event.target.value = ""
  }

  const sendDisabled = !workspace || !message.trim() || !hasCompletedSource || sending

  const playgroundContent = (
    <div
      className="ov-chat-surface flex h-[calc(100dvh-4.25rem)] flex-col"
    >
      <div className="flex min-h-0 flex-1">
        <div className="flex min-w-0 flex-1 flex-col">

      <div
        ref={chatScrollRef}
        className="ov-chat-scroll min-h-0 flex-1 overflow-y-auto"
        onScroll={(event) => {
          const element = event.currentTarget
          followLatestRef.current = element.scrollHeight - element.scrollTop - element.clientHeight < 64
        }}
      >
        <div className={cn("mx-auto flex w-full max-w-3xl flex-col px-4 sm:px-6", messages.length === 0 ? "h-full py-3 sm:py-4" : "min-h-full py-8")}>
          {messages.length === 0 ? (
            <div key={heroAnimationKey} className={cn("ov-chat-empty my-auto flex w-full max-w-[900px] flex-col items-center py-4 text-center sm:py-6", recentUploads.length > 0 && "ov-chat-empty-with-uploads")}>
              <div className="ov-chat-eyebrow ov-up" style={{ animationDelay: ".15s" }}><span />{t("heroEyebrow")}</div>
              <HeroHeading line1={t("heroTitleFirst")} line2={t("heroTitleSecond")} />
              <p className="ov-up mb-[34px] text-sm leading-6 text-muted-foreground sm:text-base" style={{ animationDelay: ".35s" }}>
                {t("heroDescription")}
              </p>
              <div className="ov-upload-stage ov-up w-full" style={{ animationDelay: ".45s" }}>
                <span className="ov-file-chip ov-file-chip-pdf">PDF</span>
                <span className="ov-file-chip ov-file-chip-xlsx">XLSX</span>
                <span className="ov-file-chip ov-file-chip-docx">DOCX</span>
                <button
                  type="button"
                  onClick={() => fileInputRef.current?.click()}
                  className="ov-chat-dropzone flex w-full items-center justify-center gap-4 text-left"
                >
                  <svg className="ov-upload-border" aria-hidden="true"><rect x="1" y="1" width="calc(100% - 2px)" height="calc(100% - 2px)" rx="23" /></svg>
                  <span className="grid size-14 shrink-0 place-items-center rounded-2xl bg-[#c8f53c] text-[#07090c]">
                    <Upload className="size-6" strokeWidth={2.4} />
                  </span>
                  <span>
                    <span className="ov-dropzone-title block text-lg font-bold">{t("heroDropTitle")}</span>
                    <span className="ov-dropzone-description mt-1 block text-sm">{t("heroDropDescription")}</span>
                  </span>
                </button>
              </div>
              {sourceProcessList}
            </div>
          ) : (
            <div className="flex flex-1 flex-col justify-end gap-5 py-4">
              {messages.map((item) => (
                <div
                  key={item.id}
                  className={cn("flex", item.role === "user" ? "justify-end" : "justify-start")}
                >
                  {item.role === "user" ? (
                    <div className="max-w-[85%] whitespace-pre-wrap break-words rounded-2xl rounded-br-md bg-primary px-4 py-3 text-sm leading-6 text-primary-foreground sm:max-w-[75%]">
                      {item.content}
                    </div>
                  ) : item.loading ? (
                    <div className="w-full py-2" aria-busy="true">
                      <div role="status" className="mb-2 inline-flex items-center gap-2 text-xs font-medium text-muted-foreground">
                        <span>{item.content ? t("responding") : t("thinking")}</span>
                        <span className="inline-flex gap-1" aria-hidden="true">
                          <span className="chat-thinking-dot size-1 rounded-full bg-primary" />
                          <span className="chat-thinking-dot size-1 rounded-full bg-primary" />
                          <span className="chat-thinking-dot size-1 rounded-full bg-primary" />
                        </span>
                      </div>
                      {item.content && (
                        <div className="chat-preview-enter whitespace-pre-wrap break-words text-sm leading-7 text-foreground sm:text-[15px]">
                          {item.content}
                          <span aria-hidden="true" className="chat-live-cursor ml-1 inline-block h-4 w-0.5 translate-y-0.5 rounded-full bg-primary" />
                        </div>
                      )}
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

      <div className="ov-chat-composer-area shrink-0 px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-3 sm:px-6 sm:pb-5">
        <form onSubmit={submitMessage} className="mx-auto w-full max-w-[720px]">
          {messages.length > 0 && sourceProcessList}

          <div className={cn("ov-chat-composer relative rounded-2xl border border-border bg-card p-1.5 shadow-[0_8px_30px_rgba(15,23,42,0.08)] focus-within:border-primary focus-within:ring-2 focus-within:ring-primary/20 sm:p-2", messages.length === 0 && "ov-chat-composer-empty ov-up")} style={messages.length === 0 ? { animationDelay: ".55s" } : undefined}>
            <textarea
              value={message}
              onChange={(event) => setMessage(event.target.value)}
              onKeyDown={handleComposerKeyDown}
              rows={messages.length === 0 ? 1 : 2}
              placeholder={
                hasCompletedSource
                  ? t("questionPlaceholder")
                  : t("uploadPlaceholder")
              }
              className="block max-h-36 min-h-16 w-full resize-none bg-transparent px-2 py-2 text-base leading-6 text-foreground outline-none placeholder:text-muted-foreground sm:min-h-14 sm:px-2 sm:py-1.5 sm:text-sm"
              aria-label={t("message")}
            />
            <div className="flex h-9 items-center justify-between gap-2">
              <div className="relative">
                <button
                  type="button"
                  disabled={sending}
                  onClick={() => fileInputRef.current?.click()}
                  className={cn(
                    "flex h-9 items-center gap-1.5 rounded-lg px-2 text-xs text-muted-foreground hover:bg-muted hover:text-foreground sm:px-2.5 sm:text-sm",
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
              <button
                type={sending ? "button" : "submit"}
                disabled={!sending && sendDisabled}
                onClick={sending ? cancelMessage : undefined}
                className={cn(
                  "grid size-9 shrink-0 place-items-center rounded-lg text-white transition-colors",
                  sending
                    ? "bg-foreground text-background hover:bg-foreground/80"
                    : "bg-primary text-primary-foreground hover:bg-primary/80",
                  !sending && sendDisabled &&
                    "cursor-not-allowed bg-muted text-muted-foreground hover:bg-muted",
                )}
                aria-label={sending ? t("cancelResponse") : t("sendMessage")}
              >
                {sending ? <Square className="size-3.5 fill-current" /> : <ArrowUp className="size-4" />}
              </button>
            </div>
          </div>
          <p className="mt-2 text-center text-[11px] text-muted-foreground">
            {t("uploadNotice")}
          </p>
        </form>
      </div>
        </div>
      </div>
    </div>
  )

  return (
    <AppShell contentClassName="ov-main-chat p-0 sm:p-0">
      {playgroundContent}
    </AppShell>
  )
}

function HeroHeading({ line1, line2 }: { line1: string; line2: string }) {
  const ref = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    const totalChars = line1.length + line2.length
    const delay = 250 + 300 + totalChars * 18 + 400
    const timer = setTimeout(() => ref.current?.classList.add("ov-shine-active"), delay)
    return () => clearTimeout(timer)
  }, [line1, line2])

  return (
    <h2 ref={ref} className="ov-shine mb-4">
      <span className="ov-slide-up-line block" style={{ "--line-index": 0 } as React.CSSProperties}>
        {line1.split("").map((char, i) => (
          <span key={i} className="ov-slide-up-char" style={{ "--char-index": i } as React.CSSProperties}>
            {char === " " ? "\u00A0" : char}
          </span>
        ))}
      </span>
      <span className="ov-slide-up-line block" style={{ "--line-index": 1 } as React.CSSProperties}>
        {line2.split("").map((char, i) => (
          <span key={i} className="ov-slide-up-char" style={{ "--char-index": i } as React.CSSProperties}>
            {char === " " ? "\u00A0" : char}
          </span>
        ))}
      </span>
    </h2>
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
        <Loader2 className="size-8 animate-spin text-primary" aria-label={t("loading")} />
      </div>
    )
  }

  return <Playground />
}
