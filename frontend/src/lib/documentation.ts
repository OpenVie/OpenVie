import type { AppLocale } from "@/i18n/routing"

export type DocumentationGroupId = "start" | "product"

export type DocumentationSection = {
  id: string
  title: string
  keywords: string[]
}

export type DocumentationPage = {
  href: string
  title: string
  description: string
  group: DocumentationGroupId
  keywords: string[]
  sections: DocumentationSection[]
}

type LocalizedPage = Omit<DocumentationPage, "href" | "group" | "sections"> & {
  sections: Record<string, { title: string; keywords?: string[] }>
}

const structure = [
  { href: "/documentation", group: "start", sections: ["core-concepts", "quickstarts", "start-paths"] },
  { href: "/documentation/getting-started", group: "start", sections: ["upload", "index", "test", "next-step"] },
  { href: "/documentation/documents", group: "product", sections: ["supported-files", "indexing", "viewer-citations", "manage"] },
  { href: "/documentation/playground", group: "product", sections: ["ask", "sources", "history", "manage"] },
] as const

export const documentationGroupOrder: DocumentationGroupId[] = ["start", "product"]

const groupTitles: Record<AppLocale, Record<DocumentationGroupId, string>> = {
  en: { start: "Start here", product: "Product guides" },
  vi: { start: "Bắt đầu", product: "Hướng dẫn sản phẩm" },
}

const content: Record<AppLocale, Record<string, LocalizedPage>> = {
  en: {
    "/documentation": {
      title: "Documentation overview",
      description: "Learn the OpenVie workflow and choose the right path for your role.",
      keywords: ["overview", "concepts", "quickstart", "roles"],
      sections: {
        "core-concepts": { title: "Core concepts", keywords: ["workspace", "knowledge base", "chat"] },
        quickstarts: { title: "Quickstarts by role", keywords: ["member", "administrator", "first steps"] },
        "start-paths": { title: "Where to start", keywords: ["chat", "documents", "invitations"] },
      },
    },
    "/documentation/getting-started": {
      title: "Getting started",
      description: "Upload your first source, wait for indexing, and test an answer.",
      keywords: ["upload", "indexing", "first answer", "setup"],
      sections: { upload: { title: "1. Upload a document" }, index: { title: "2. Wait for indexing" }, test: { title: "3. Test the playground" }, "next-step": { title: "4. Choose what to explore next" } },
    },
    "/documentation/documents": {
      title: "Documents",
      description: "Manage source files, indexing, search, citations, and deletion.",
      keywords: ["pdf", "docx", "xlsx", "csv", "indexing", "citations"],
      sections: { "supported-files": { title: "Supported files and limits" }, indexing: { title: "Indexing statuses" }, "viewer-citations": { title: "Viewer and citations" }, manage: { title: "Search, filter, and delete" } },
    },
    "/documentation/playground": {
      title: "Chat playground",
      description: "Ask employee questions, inspect sources, and manage chat history.",
      keywords: ["chat", "history", "sources", "citations"],
      sections: { ask: { title: "Ask questions" }, sources: { title: "Review sources and citations" }, history: { title: "Search chat history" }, manage: { title: "Manage chats" } },
    },
  },
  vi: {
    "/documentation": {
      title: "Tổng quan tài liệu",
      description: "Tìm hiểu quy trình OpenVie và chọn lộ trình phù hợp với vai trò của bạn.",
      keywords: ["tong quan", "khai niem", "bat dau nhanh", "vai tro"],
      sections: {
        "core-concepts": { title: "Các khái niệm cốt lõi", keywords: ["không gian làm việc", "kho tri thức", "trò chuyện"] },
        quickstarts: { title: "Bắt đầu nhanh theo vai trò", keywords: ["thành viên", "quản trị viên", "các bước đầu tiên"] },
        "start-paths": { title: "Nên bắt đầu từ đâu", keywords: ["trò chuyện", "tài liệu", "lời mời"] },
      },
    },
    "/documentation/getting-started": {
      title: "Bắt đầu",
      description: "Tải nguồn dữ liệu đầu tiên lên, chờ lập chỉ mục và thử một câu trả lời.",
      keywords: ["tải lên", "lập chỉ mục", "câu trả lời đầu tiên", "thiết lập"],
      sections: { upload: { title: "1. Tải tài liệu lên" }, index: { title: "2. Chờ lập chỉ mục" }, test: { title: "3. Thử khu trò chuyện" }, "next-step": { title: "4. Chọn bước tiếp theo" } },
    },
    "/documentation/documents": {
      title: "Tài liệu",
      description: "Quản lý tệp nguồn, lập chỉ mục, tìm kiếm, trích dẫn và xóa.",
      keywords: ["pdf", "docx", "xlsx", "csv", "lập chỉ mục", "trích dẫn"],
      sections: { "supported-files": { title: "Tệp được hỗ trợ và giới hạn" }, indexing: { title: "Trạng thái lập chỉ mục" }, "viewer-citations": { title: "Trình xem và trích dẫn" }, manage: { title: "Tìm kiếm, lọc và xóa" } },
    },
    "/documentation/playground": {
      title: "Bảng trò chuyện",
      description: "Đặt câu hỏi cho nhân viên, xem nguồn và quản lý lịch sử hội thoại.",
      keywords: ["trò chuyện", "lịch sử", "nguồn", "trích dẫn"],
      sections: { ask: { title: "Đặt câu hỏi" }, sources: { title: "Xem nguồn và trích dẫn" }, history: { title: "Tìm lịch sử hội thoại" }, manage: { title: "Quản lý cuộc trò chuyện" } },
    },
  },
}

export function getDocumentationGroups(locale: AppLocale) {
  return documentationGroupOrder.map((id) => ({ id, title: groupTitles[locale][id] }))
}

export function getDocumentationPages(locale: AppLocale): DocumentationPage[] {
  return structure.map((page) => {
    const localized = content[locale][page.href]
    return {
      href: page.href,
      group: page.group,
      title: localized.title,
      description: localized.description,
      keywords: localized.keywords,
      sections: page.sections.map((id) => ({
        id,
        title: localized.sections[id].title,
        keywords: localized.sections[id].keywords ?? [],
      })),
    }
  })
}

export function documentationPage(pathname: string, locale: AppLocale = "en") {
  const normalized = pathname !== "/" ? pathname.replace(/\/$/, "") : pathname
  const pages = getDocumentationPages(locale)
  return pages.find((page) => page.href === normalized) ?? pages[0]
}

export function normalizeDocumentationSearch(value: string) {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/đ/g, "d")
    .replace(/Đ/g, "D")
    .toLocaleLowerCase()
}
