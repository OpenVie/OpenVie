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
  { href: "/organization", labelKey: "organization", icon: ShieldCheck, ownerOnly: true },
  { href: "/workspaces", labelKey: "workspaces", icon: Building2, ownerOnly: true },
  { href: "/settings", labelKey: "settings", icon: Settings, ownerOnly: true },
  { href: "/documentation", labelKey: "documentation", icon: BookOpen, placement: "footer" },
]

/** Items visible to a caller: owner-only destinations require ORG_OWNER. */
export function visibleNavigation(orgRole: string | undefined): AppNavigationItem[] {
  return appNavigation.filter((item) => !item.ownerOnly || orgRole === "ORG_OWNER")
}

// ─── Soft admin boundary (Keycloak-ready) ─────────────────────────────────────
// Logical split of the single Next.js app: owner-only destinations form the
// future `admin:3001` surface, everything else stays on `frontend:3000`.
// Route groups `(admin)` vs `(dashboard)` share the same URLs today, so this
// list is the contract the gateway (`/api/v1/admin/*`) and the Keycloak
// `openvie-admin` client will enforce after the hard split.
export const ADMIN_PATHS = ["/organization", "/workspaces", "/settings"] as const

export type AdminPath = (typeof ADMIN_PATHS)[number]

/** Owner-only destinations that will move behind the admin surface. */
export const adminNavigation: AppNavigationItem[] = appNavigation.filter(
  (item) => item.ownerOnly,
)

/** True for pages guarded by the `(admin)` layout today. */
export function isAdminPath(pathname: string | undefined): boolean {
  if (!pathname) return false
  return (ADMIN_PATHS as readonly string[]).some(
    (adminPath) => pathname === adminPath || pathname.endsWith(adminPath),
  )
}
