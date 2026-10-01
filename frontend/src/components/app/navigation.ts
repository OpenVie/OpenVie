import {
  BookOpen,
  Building2,
  FileText,
  MessageSquare,
  Settings,
  Users,
  type LucideIcon,
} from "lucide-react"

export type AppNavigationItem = {
  href: string
  labelKey: "chat" | "documents" | "documentation" | "users" | "workspaces" | "settings"
  icon: LucideIcon
  placement?: "main" | "footer"
  /** When set, the item only shows for callers holding an org-level role. */
  ownerOnly?: boolean
}

export const appNavigation: AppNavigationItem[] = [
  { href: "/", labelKey: "chat", icon: MessageSquare },
  { href: "/documents", labelKey: "documents", icon: FileText },
  { href: "/users", labelKey: "users", icon: Users },
  { href: "/workspaces", labelKey: "workspaces", icon: Building2, ownerOnly: true },
  { href: "/settings", labelKey: "settings", icon: Settings, ownerOnly: true },
  { href: "/documentation", labelKey: "documentation", icon: BookOpen, placement: "footer" },
]

/** Items visible to a caller: owner-only destinations require ORG_OWNER. */
export function visibleNavigation(orgRole: string | undefined): AppNavigationItem[] {
  return appNavigation.filter((item) => !item.ownerOnly || orgRole === "ORG_OWNER")
}
