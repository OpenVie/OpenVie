import {
  BookOpen,
  FileText,
  MessageSquare,
  Users,
  type LucideIcon,
} from "lucide-react"

export type AppNavigationItem = {
  href: string
  labelKey: "chat" | "documents" | "documentation" | "users"
  icon: LucideIcon
  placement?: "main" | "footer"
}

export const appNavigation: AppNavigationItem[] = [
  { href: "/", labelKey: "chat", icon: MessageSquare },
  { href: "/documents", labelKey: "documents", icon: FileText },
  { href: "/users", labelKey: "users", icon: Users },
  { href: "/documentation", labelKey: "documentation", icon: BookOpen, placement: "footer" },
]
