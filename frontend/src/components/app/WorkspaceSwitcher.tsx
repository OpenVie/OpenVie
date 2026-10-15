"use client"

import { useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { ChevronDown, Loader2 } from "lucide-react"
import { useAuthStore } from "@/components/providers/StoreProvider"
import { listWorkspacesApi, switchWorkspaceApi } from "@/lib/auth-api"
import type { WorkspaceSummary } from "@/types"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { cn } from "@/lib/utils"

let workspaceCache: { accessToken: string; workspaces: WorkspaceSummary[] } | null = null

function cachedWorkspaces(accessToken: string | null) {
  return accessToken && workspaceCache?.accessToken === accessToken
    ? workspaceCache.workspaces
    : []
}

/**
 * Active-workspace picker. Switching re-issues the session token scoped to
 * the chosen workspace, then reloads the route so every workspace-scoped
 * surface (chat, documents, users) refetches against the new context.
 */
export function WorkspaceSwitcher() {
  const t = useTranslations("WorkspaceSwitcher")
  const user = useAuthStore((s) => s.user)
  const accessToken = useAuthStore((s) => s.accessToken)
  const setAuth = useAuthStore((s) => s.setAuth)
  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>(() => cachedWorkspaces(accessToken))
  const [open, setOpen] = useState(false)
  const [switching, setSwitching] = useState(false)

  useEffect(() => {
    if (!accessToken) return
    let active = true
    listWorkspacesApi(accessToken)
      .then((list) => {
        if (!active) return
        workspaceCache = { accessToken, workspaces: list }
        setWorkspaces(list)
      })
      .catch(() => {
        if (active) setWorkspaces([])
      })
    return () => {
      active = false
    }
  }, [accessToken])

  if (!user || workspaces.length === 0) return null

  const activeWorkspace = workspaces.find(
    (workspace) => workspace.id === user.activeWorkspaceId,
  )

  async function selectWorkspace(target: WorkspaceSummary) {
    if (target.id === user?.activeWorkspaceId) {
      setOpen(false)
      return
    }
    setSwitching(true)
    try {
      const res = await switchWorkspaceApi(target.id, accessToken)
      setAuth(res.user, res.accessToken)
      window.location.reload()
    } catch (err) {
      console.error("Workspace switch failed", err)
      setSwitching(false)
      setOpen(false)
    }
  }

  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger
        render={
          <button
            type="button"
            className="ov-workspace-trigger"
            aria-label={t("label")}
          />
        }
      >
        <span className="min-w-0 flex-1 truncate text-left">
          {activeWorkspace?.name ?? user.activeWorkspaceId}
        </span>
        {switching ? (
          <Loader2 className="size-3.5 shrink-0 animate-spin" />
        ) : (
          <ChevronDown className="size-3.5 shrink-0" strokeWidth={2.2} />
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="ov-workspace-menu w-56">
        <DropdownMenuGroup>
          <DropdownMenuLabel>{t("workspaces")}</DropdownMenuLabel>
          <DropdownMenuSeparator />
          {workspaces.map((workspace) => (
            <DropdownMenuItem
              key={workspace.id}
              onClick={() => void selectWorkspace(workspace)}
              onSelect={() => void selectWorkspace(workspace)}
              className={cn(
                "justify-between",
                workspace.id === user.activeWorkspaceId && "font-medium",
              )}
            >
              <span className="truncate">{workspace.name}</span>
              <span className="ml-2 text-xs text-[#a9b3ab]">
                {workspace.role === "WORKSPACE_ADMIN" ? t("admin") : t("member")}
              </span>
            </DropdownMenuItem>
          ))}
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
