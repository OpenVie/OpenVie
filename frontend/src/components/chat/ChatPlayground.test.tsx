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

import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"
import toast from "react-hot-toast"

import ChatPlayground from "@/components/chat/ChatPlayground"
import * as ChatApiModule from "@/lib/chat-api"
import { hasAnyInstalledBizAppApi } from "@/lib/bizapp-api"
import {
  createChatSessionApi,
  listPlaygroundSessionsApi,
  submitChatMessageApi,
} from "@/lib/chat-api"
import * as DocumentsApiModule from "@/lib/documents-api"
import { listDocumentsApi } from "@/lib/documents-api"
import { getTenantWorkspaceApi } from "@/lib/workspace-api"
import type { TenantWorkspace } from "@/types"

vi.mock("next-intl", () => ({
  useTranslations: () => (key: string) => key,
  useFormatter: () => ({ number: (value: number) => String(value) }),
}))

vi.mock("@/i18n/navigation", () => ({
  useRouter: () => ({ replace: vi.fn() }),
}))

vi.mock("@/hooks/useTokenRehydration", () => ({
  useTokenRehydration: () => "authenticated",
}))

vi.mock("@/hooks/useApiClient", () => ({
  useApiClient: () => ({ request: vi.fn() }),
}))

vi.mock("@/components/app/AppShell", () => ({
  AppShell: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

vi.mock("@/components/chat/AssistantResponse", () => ({
  AssistantResponse: ({ content }: { content: string }) => <div>{content}</div>,
}))

vi.mock("@/lib/workspace-api", () => ({
  getTenantWorkspaceApi: vi.fn(),
}))

vi.mock("@/lib/documents-api", async (importOriginal) => {
  const actual = await importOriginal<typeof DocumentsApiModule>()
  return {
    ...actual,
    listDocumentsApi: vi.fn(),
    getDocumentStatusApi: vi.fn(),
    uploadDocumentApi: vi.fn(),
  }
})

vi.mock("@/lib/bizapp-api", () => ({
  hasAnyInstalledBizAppApi: vi.fn(),
}))

vi.mock("@/lib/chat-api", async (importOriginal) => {
  const actual = await importOriginal<typeof ChatApiModule>()
  return {
    ...actual,
    createChatSessionApi: vi.fn(),
    listPlaygroundSessionsApi: vi.fn(),
    getChatMessagesApi: vi.fn(),
    submitChatMessageApi: vi.fn(),
  }
})

vi.mock("react-hot-toast", () => ({
  default: { error: vi.fn(), success: vi.fn() },
}))

const WORKSPACE: TenantWorkspace = {
  tenantId: "tenant-1",
  knowledgeBase: { id: "kb-1", name: "KB", slug: "kb", defaultLocale: "vi" },
  chatbot: {
    id: "bot-1",
    displayName: "Bot",
    defaultLocale: "vi",
    welcomeMessage: "",
  },
}

async function sendMessage(content: string) {
  const composer = screen.getByRole("textbox", { name: "message" })
  fireEvent.change(composer, { target: { value: content } })
  fireEvent.keyDown(composer, { key: "Enter" })
}

describe("ChatPlayground composer gating", () => {
  beforeEach(() => {
    vi.mocked(getTenantWorkspaceApi).mockResolvedValue(WORKSPACE)
    vi.mocked(listDocumentsApi).mockResolvedValue([])
    vi.mocked(listPlaygroundSessionsApi).mockResolvedValue({ items: [], nextCursor: null })
    vi.mocked(createChatSessionApi).mockResolvedValue({
      id: "session-1",
      chatbot_id: "bot-1",
      knowledge_base_id: "kb-1",
      tenant_id: "tenant-1",
      locale: "vi",
    })
    vi.mocked(submitChatMessageApi).mockResolvedValue({
      id: "message-1",
      role: "assistant",
      content: "Đã ghi nhận.",
      citations: [],
    })
  })

  it("sends messages in a document-less workspace when the organization has an app installed", async () => {
    vi.mocked(hasAnyInstalledBizAppApi).mockResolvedValue(true)

    render(<ChatPlayground />)

    await waitFor(() => {
      expect(screen.getByRole("textbox", { name: "message" })).toHaveAttribute(
        "placeholder",
        "questionPlaceholder",
      )
    })

    await sendMessage("Tôi muốn xin nghỉ.")

    await waitFor(() => {
      expect(submitChatMessageApi).toHaveBeenCalledWith(
        expect.anything(),
        "session-1",
        "Tôi muốn xin nghỉ.",
        expect.anything(),
      )
    })
  })

  it("blocks messages when neither a document nor an app exists", async () => {
    vi.mocked(hasAnyInstalledBizAppApi).mockResolvedValue(false)

    render(<ChatPlayground />)

    await waitFor(() => {
      expect(screen.getByRole("textbox", { name: "message" })).toHaveAttribute(
        "placeholder",
        "uploadPlaceholder",
      )
    })

    await sendMessage("Tôi muốn xin nghỉ.")

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledWith("uploadBeforeQuestion")
    })
    expect(submitChatMessageApi).not.toHaveBeenCalled()
  })
})
