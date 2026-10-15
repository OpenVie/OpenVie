import {
  BookOpen,
  Building2,
  FileText,
  MessageSquare,
  Settings,
  ShieldCheck,
  Users,
  type LucideIcon,
} from "lucide-react"

export type AppNavigationItem = {
  href: string
  labelKey: "chat" | "documents" | "documentation" | "users" | "organization" | "workspaces" | "settings"
  icon: LucideIcon
  placement?: "main" | "footer"
  /** When set, the item only shows for callers holding an org-level role. */
  ownerOnly?: boolean
}

export const appNavigation: AppNavigationItem[] = [
  { href: "/", labelKey: "chat", icon: MessageSquare },
  { href: "/documents", labelKey: "documents", icon: FileText },
  { href: "/users", labelKey: "users", icon: Users },
  { href: "/admin/organization", labelKey: "organization", icon: ShieldCheck, ownerOnly: true },
  { href: "/admin/workspaces", labelKey: "workspaces", icon: Building2, ownerOnly: true },
  { href: "/admin/settings", labelKey: "settings", icon: Settings, ownerOnly: true },
  { href: "/documentation", labelKey: "documentation", icon: BookOpen, placement: "footer" },
]

/** Items visible to a caller: owner-only destinations require ORG_OWNER. */
export function visibleNavigation(orgRole: string | undefined): AppNavigationItem[] {
  return appNavigation.filter((item) => !item.ownerOnly || orgRole === "ORG_OWNER")
}

// ─── Admin surface ──────────────────────────────────────────────────────────
// Owner-only destinations live under `/admin/*` and are served by the
// `frontend-admin` deployment (`:3001`, OIDC client `openvie-admin`);
// everything else stays on the main app (`:3000`, `openvie-frontend`).
// The gateway routes `/admin/*` to the admin deployment and
// `/api/v1/admin/*` to Spring, which still enforces `ORG_OWNER` per request.
export const ADMIN_PATHS = ["/admin/organization", "/admin/workspaces", "/admin/settings"] as const

export type AdminPath = (typeof ADMIN_PATHS)[number]

/** Owner-only destinations served behind the admin surface. */
export const adminNavigation: AppNavigationItem[] = appNavigation.filter(
  (item) => item.ownerOnly,
)

/** True for pages guarded by the `(admin)` layout. */
export function isAdminPath(pathname: string | undefined): boolean {
  if (!pathname) return false
  return (ADMIN_PATHS as readonly string[]).some(
    (adminPath) => pathname === adminPath || pathname.endsWith(adminPath),
  )
}
