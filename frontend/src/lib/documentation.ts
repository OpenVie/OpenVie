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

import type { AppLocale } from "@/i18n/routing"

export type DocumentationGroupId = "start" | "product" | "admin" | "bizapps"

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
  { href: "/documentation/auth", group: "start", sections: ["password-sso", "first-login", "switch-workspace", "recover-access"] },
  { href: "/documentation/documents", group: "product", sections: ["supported-files", "indexing", "viewer-citations", "manage"] },
  { href: "/documentation/playground", group: "product", sections: ["ask", "sources", "history", "manage"] },
  { href: "/documentation/workflow", group: "product", sections: ["approval-levels", "task-inbox", "sla-timers", "role-permissions"] },
  { href: "/documentation/members", group: "admin", sections: ["org-accounts", "create-account", "invite-workspace", "password-status"] },
  { href: "/documentation/workspaces", group: "admin", sections: ["default-workspace", "visibility", "lifecycle", "membership"] },
  { href: "/documentation/settings", group: "admin", sections: ["organization", "self-registration", "email-channels"] },
  { href: "/documentation/api-keys", group: "admin", sections: ["when", "create", "secret-once", "manage", "first-calls"] },
  { href: "/documentation/bizapps", group: "bizapps", sections: ["overview", "marketplace-install", "runtime-views", "credential-vault"] },
  { href: "/documentation/composer", group: "bizapps", sections: ["canvas-editing", "blocks-entities", "ai-drafting", "template-exchange"] },
] as const

export const documentationGroupOrder: DocumentationGroupId[] = ["start", "product", "admin", "bizapps"]

const groupTitles: Record<AppLocale, Record<DocumentationGroupId, string>> = {
  en: { start: "Start here", product: "Knowledge & Approvals", admin: "Organization admin", bizapps: "BizApp Platform" },
  vi: { start: "Bắt đầu", product: "Tri thức & Phê duyệt", admin: "Quản trị tổ chức", bizapps: "Nền tảng BizApp" },
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
    "/documentation/auth": {
      title: "Sign in & sessions",
      description: "Password and SSO sign-in, first-login password change, workspace switching, and account recovery.",
      keywords: ["login", "sso", "keycloak", "password", "workspace switch", "recovery"],
      sections: {
        "password-sso": { title: "1. Password or SSO sign-in" },
        "first-login": { title: "2. First login & password change" },
        "switch-workspace": { title: "3. Switch workspace & language" },
        "recover-access": { title: "4. Lost access" },
      },
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
      "/documentation/workflow": {
        title: "Workflow & approvals",
        description: "Two-level document review, task inbox handling, and SLA timer escalations.",
        keywords: ["workflow", "approval", "flowable", "bpmn", "sla", "tasks"],
        sections: {
          "approval-levels": { title: "1. Two-level review process" },
          "task-inbox": { title: "2. Claiming & completing tasks" },
          "sla-timers": { title: "3. SLA reminder & escalation timers" },
          "role-permissions": { title: "4. Role permissions by level" },
        },
      },
      "/documentation/bizapps": {
        title: "BizApps platform",
        description: "Installable applications, runtime records, timelines, and the credential vault.",
        keywords: ["bizapps", "crm", "booking", "signature", "vault", "marketplace"],
        sections: {
          overview: { title: "1. What is a BizApp?" },
          "marketplace-install": { title: "2. Installing from the marketplace" },
          "runtime-views": { title: "3. Interacting with runtime views" },
          "credential-vault": { title: "4. AES-256-GCM credential vault" },
        },
      },
      "/documentation/composer": {
        title: "Visual composer & AI drafting",
        description: "Build apps on the visual canvas, draft with AI prompts, and exchange templates.",
        keywords: ["composer", "canvas", "react flow", "ai drafting", "manifest", "templates"],
        sections: {
          "canvas-editing": { title: "1. Visual canvas & layout" },
          "blocks-entities": { title: "2. Configuring entities & blocks" },
          "ai-drafting": { title: "3. Drafting apps with AI" },
          "template-exchange": { title: "4. Export, import & remix templates" },
        },
      },
    "/documentation/members": {
      title: "Accounts & members",
      description: "Create organization accounts, invite members, and manage passwords or status.",
      keywords: ["users", "accounts", "invitations", "password", "activate", "deactivate", "owner"],
      sections: {
        "org-accounts": { title: "1. Organization accounts vs workspace membership" },
        "create-account": { title: "2. Creating an organization account" },
        "invite-workspace": { title: "3. Inviting members to a workspace" },
        "password-status": { title: "4. Passwords, activation, and removal" },
      },
    },
    "/documentation/workspaces": {
      title: "Workspaces",
      description: "Default workspace semantics, public versus private spaces, lifecycle, and membership.",
      keywords: ["workspace", "general", "public", "private", "archive", "membership", "delete documents"],
      sections: {
        "default-workspace": { title: "1. The default workspace" },
        visibility: { title: "2. Public vs private" },
        lifecycle: { title: "3. Create, rename, archive" },
        membership: { title: "4. Membership and document permissions" },
      },
    },
    "/documentation/settings": {
      title: "Organization settings",
      description: "Organization profile, self-registration toggle, and SMTP email channels.",
      keywords: ["settings", "self registration", "register", "smtp", "email channel", "test email"],
      sections: {
        organization: { title: "1. Organization profile" },
        "self-registration": { title: "2. Allow self-registration" },
        "email-channels": { title: "3. Email notification channels" },
      },
    },
    "/documentation/api-keys": {
      title: "API keys & integrations",
      description: "Let another internal application call the chat API: create a machine key, store the one-time secret, and make the first calls.",
      keywords: ["api", "api key", "integration", "machine", "bearer", "token", "curl", "chat api"],
      sections: {
        when: { title: "1. When to use an API key" },
        create: { title: "2. Creating a key" },
        "secret-once": { title: "3. The one-time secret" },
        manage: { title: "4. Listing, usage, and revocation" },
        "first-calls": { title: "5. Your first three calls" },
      },
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
    "/documentation/auth": {
      title: "Đăng nhập & phiên",
      description: "Đăng nhập bằng mật khẩu hoặc SSO, đổi mật khẩu lần đăng nhập đầu, chuyển workspace và phục hồi quyền truy cập.",
      keywords: ["dang nhap", "sso", "keycloak", "mat khau", "chuyen workspace", "phuc hoi"],
      sections: {
        "password-sso": { title: "1. Đăng nhập bằng mật khẩu hoặc SSO" },
        "first-login": { title: "2. Lần đăng nhập đầu & đổi mật khẩu" },
        "switch-workspace": { title: "3. Chuyển workspace & đổi ngôn ngữ" },
        "recover-access": { title: "4. Khi mất quyền truy cập" },
      },
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
      "/documentation/workflow": {
        title: "Quy trình phê duyệt",
        description: "Luồng phê duyệt tài liệu hai cấp, hộp thư công việc và bộ đếm thời gian SLA.",
        keywords: ["quy trinh", "phe duyet", "flowable", "bpmn", "sla", "tac vu"],
        sections: {
          "approval-levels": { title: "1. Luồng phê duyệt hai cấp" },
          "task-inbox": { title: "2. Tiếp nhận & xử lý tác vụ" },
          "sla-timers": { title: "3. Bộ đếm SLA nhắc nhở & chuyển tiếp" },
          "role-permissions": { title: "4. Phân định quyền theo vai trò" },
        },
      },
      "/documentation/members": {
        title: "Tài khoản & thành viên",
        description: "Tạo tài khoản của tổ chức, mời thành viên vào workspace và quản lý mật khẩu, trạng thái.",
        keywords: ["tai khoan", "nguoi dung", "moi", "mat khau", "kich hoat", "vo hieu hoa", "chu so huu"],
        sections: {
          "org-accounts": { title: "1. Tài khoản tổ chức & thành viên workspace" },
          "create-account": { title: "2. Tạo tài khoản tổ chức" },
          "invite-workspace": { title: "3. Mời thành viên vào workspace" },
          "password-status": { title: "4. Mật khẩu, kích hoạt và gỡ bỏ" },
        },
      },
      "/documentation/workspaces": {
        title: "Không gian làm việc",
        description: "Workspace mặc định, không gian công khai so với riêng tư, vòng đời và tình trạng thành viên.",
        keywords: ["workspace", "general", "cong khai", "rieng tu", "luu tru", "thanh vien", "xoa tai lieu"],
        sections: {
          "default-workspace": { title: "1. Workspace mặc định" },
          visibility: { title: "2. Công khai so với riêng tư" },
          lifecycle: { title: "3. Tạo, đổi tên, lưu trữ" },
          membership: { title: "4. Thành viên & quyền tài liệu" },
        },
      },
      "/documentation/settings": {
        title: "Cài đặt tổ chức",
        description: "Hồ sơ tổ chức, bật/tắt tự đăng ký và kênh email SMTP.",
        keywords: ["cai dat", "tu dang ky", "dang ky", "smtp", "kenh email", "email thu nghiem"],
        sections: {
          organization: { title: "1. Hồ sơ tổ chức" },
          "self-registration": { title: "2. Cho phép tự đăng ký" },
          "email-channels": { title: "3. Kênh thư thông báo" },
        },
      },
      "/documentation/api-keys": {
        title: "API key & tích hợp",
        description: "Cho phép một ứng dụng nội bộ khác gọi chat API: tạo khóa máy, lưu secret một lần và thực hiện các lời gọi đầu tiên.",
        keywords: ["api", "api key", "tich hop", "may", "bearer", "token", "curl", "chat api"],
        sections: {
          when: { title: "1. Khi nào dùng API key" },
          create: { title: "2. Tạo khóa" },
          "secret-once": { title: "3. Secret chỉ hiện một lần" },
          manage: { title: "4. Danh sách, sử dụng & thu hồi" },
          "first-calls": { title: "5. Ba lời gọi đầu tiên" },
        },
      },
      "/documentation/bizapps": {
        title: "Nền tảng BizApp",
        description: "Kho ứng dụng kinh doanh cài đặt sẵn, bản ghi tác nghiệp và kho bảo mật thông tin xác thực.",
        keywords: ["bizapps", "crm", "booking", "ky so", "vault", "kho ung dung"],
        sections: {
          overview: { title: "1. BizApp là gì?" },
          "marketplace-install": { title: "2. Duyệt & cài đặt từ kho ứng dụng" },
          "runtime-views": { title: "3. Khung xem tác vụ & bản ghi" },
          "credential-vault": { title: "4. Kho mật khẩu xác thực AES-256-GCM" },
        },
      },
      "/documentation/composer": {
        title: "Trình thiết kế trực quan & Soạn thảo AI",
        description: "Thiết kế ứng dụng trên canvas kéo thả, sinh manifest bằng prompt AI và trao đổi template.",
        keywords: ["composer", "canvas", "react flow", "ai drafting", "manifest", "mau ung dung"],
        sections: {
          "canvas-editing": { title: "1. Thao tác trên canvas & tự động sắp xếp" },
          "blocks-entities": { title: "2. Cấu hình thực thể & khối nghiệp vụ" },
          "ai-drafting": { title: "3. Soạn thảo tự động bằng AI" },
          "template-exchange": { title: "4. Xuất, nhập & remix template" },
        },
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
