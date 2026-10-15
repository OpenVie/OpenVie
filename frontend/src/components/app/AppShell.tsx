"use client"

import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from "react"
import Image from "next/image"
import { useTranslations } from "next-intl"
import { LogOut, Moon, PanelLeftClose, PanelLeftOpen, Plus, Sun } from "lucide-react"
import { useAuthStore } from "@/components/providers/StoreProvider"
import { useTokenRehydration } from "@/hooks/useTokenRehydration"
import { logoutApi } from "@/lib/auth-api"
import { visibleNavigation } from "@/components/app/navigation"
import { WorkspaceSwitcher } from "@/components/app/WorkspaceSwitcher"
import { LanguageSwitcher } from "@/components/i18n/LanguageSwitcher"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Link, usePathname, useRouter } from "@/i18n/navigation"
import { withNext } from "@/lib/auth-redirect"
import { cn } from "@/lib/utils"
import { AppLoadingScreen } from "@/components/app/AppLoadingScreen"

function initialsFrom(fullName: string | undefined): string {
  if (!fullName?.trim()) return "?"
  const parts = fullName.trim().split(/\s+/)
  if (parts.length >= 2) {
    return `${parts[0]![0]}${parts[parts.length - 1]![0]}`.toUpperCase()
  }
  return fullName.slice(0, 2).toUpperCase()
}

type AppShellProps = {
  children: ReactNode
  contentClassName?: string
  mobileNavContent?: ReactNode
}

type Theme = "light" | "dark"

function readStoredTheme(): Theme {
  if (typeof window === "undefined") return "light"
  return window.localStorage.getItem("openvie.theme") === "dark" ? "dark" : "light"
}

export function AppShell({ children, contentClassName, mobileNavContent }: AppShellProps) {
  const t = useTranslations("Navigation")
  const chat = useTranslations("Chat")
  const pathname = usePathname()
  const router = useRouter()
  const user = useAuthStore((state) => state.user)
  const clearAuth = useAuthStore((state) => state.clearAuth)
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [theme, setTheme] = useState<Theme>(readStoredTheme)
  const mainAreaRef = useRef<HTMLDivElement>(null)
  const navigationRef = useRef<HTMLElement>(null)
  const [navIndicator, setNavIndicator] = useState({ top: 0, left: 0, width: 0, height: 0, visible: false })
  const navItems = visibleNavigation(user?.orgRole)
  const primaryNavigation = navItems.filter((item) => item.placement !== "footer")
  const footerNavigation = navItems.filter((item) => item.placement === "footer")
  async function handleLogout() {
    try {
      await logoutApi()
    } catch {
      // The local session must still be cleared when the server is unavailable.
    }
    clearAuth()
    router.push("/")
  }

  useEffect(() => {
    function placeNavigationIndicator() {
      const navigation = navigationRef.current
      const activeItem = navigation?.querySelector<HTMLElement>("[data-ov-nav-active='true']")
      if (!navigation || !activeItem) return
      setNavIndicator({
        top: activeItem.offsetTop,
        left: activeItem.offsetLeft,
        width: activeItem.offsetWidth,
        height: activeItem.offsetHeight,
        visible: true,
      })
    }

    const frame = window.requestAnimationFrame(placeNavigationIndicator)
    window.addEventListener("resize", placeNavigationIndicator)
    return () => {
      window.cancelAnimationFrame(frame)
      window.removeEventListener("resize", placeNavigationIndicator)
    }
  }, [pathname, sidebarCollapsed])

  useEffect(() => {
    document.documentElement.dataset.ovTheme = theme
    document.documentElement.dataset.theme = theme
    window.localStorage.setItem("openvie.theme", theme)
    window.dispatchEvent(new Event("openvie:themechange"))
  }, [theme])

  useEffect(() => {
    const mainArea = mainAreaRef.current
    if (!mainArea || !window.matchMedia("(hover: hover)").matches) return

    const spotlight = mainArea.querySelector<HTMLElement>(".ov-spotlight")
    if (!spotlight) return

    function moveSpotlight(event: PointerEvent) {
      const bounds = mainArea!.getBoundingClientRect()
      spotlight!.style.transform = `translate(${event.clientX - bounds.left - 280}px, ${event.clientY - bounds.top - 280}px)`
      spotlight!.classList.add("ov-spotlight-visible")
    }
    function hideSpotlight() {
      spotlight!.classList.remove("ov-spotlight-visible")
    }

    mainArea.addEventListener("pointermove", moveSpotlight)
    mainArea.addEventListener("pointerleave", hideSpotlight)
    return () => {
      mainArea.removeEventListener("pointermove", moveSpotlight)
      mainArea.removeEventListener("pointerleave", hideSpotlight)
    }
  }, [])

  return (
    <div className={cn("ov-shell min-h-dvh", theme === "light" && "ov-light")}>
      <aside
        className={cn(
          "ov-sidebar fixed inset-y-0 left-0 z-40 flex w-[260px] flex-col transition-transform duration-200 xl:translate-x-0",
          sidebarOpen ? "translate-x-0" : "-translate-x-full",
          sidebarCollapsed && "ov-sidebar-collapsed",
        )}
      >
        <div className="flex items-center px-3 pb-3 pt-1">
          <Link href="/" className="ov-brand" onClick={() => setSidebarOpen(false)}>
            <span className="ov-brand-mark"><Image src="/logo.png" alt="OpenVie Logo" width={34} height={34} /></span>
            <span>OpenVie</span>
          </Link>
          <button
            type="button"
            className="ov-mobile-sidebar-close ov-icon-button ml-auto"
            onClick={() => setSidebarOpen(false)}
            aria-label={t("closeNavigation")}
          >
            <PanelLeftClose className="size-5" />
          </button>
          <button
            type="button"
            className="ov-sidebar-collapse ov-icon-button ml-auto hidden xl:grid"
            onClick={() => setSidebarCollapsed((current) => !current)}
            aria-label={sidebarCollapsed ? "Show navigation labels" : "Hide navigation labels"}
          >
            {sidebarCollapsed ? (
              <>
                <Image className="ov-sidebar-expand-logo" src="/logo.png" alt="" width={34} height={34} />
                <PanelLeftOpen className="ov-sidebar-expand-icon size-5" />
              </>
            ) : <PanelLeftClose className="size-5" />}
          </button>
        </div>

        <Link href="/" className="ov-new-chat" onClick={() => setSidebarOpen(false)} aria-label={chat("newChat")}>
          <Plus className="size-4 shrink-0" />
          <span className="ov-new-chat-label">{chat("newChat")}</span>
        </Link>

        <div className="ov-workspace-container px-1 pb-3 pt-1">
          <WorkspaceSwitcher />
        </div>

        <nav ref={navigationRef} className="scrollbar-hidden ov-navigation flex-1 overflow-y-auto px-1">
          <span
            aria-hidden="true"
            className={cn("ov-navigation-indicator", navIndicator.visible && "ov-navigation-indicator-visible")}
            style={{
              "--ov-indicator-top": `${navIndicator.top}px`,
              "--ov-indicator-left": `${navIndicator.left}px`,
              "--ov-indicator-width": `${navIndicator.width}px`,
              "--ov-indicator-height": `${navIndicator.height}px`,
            } as CSSProperties}
          />
          {primaryNavigation.map(({ href, labelKey, icon: Icon }) => {
            const isActive = pathname === href || (href !== "/" && pathname.startsWith(`${href}/`))
            return (
              <Link
                key={href}
                href={href}
                onClick={() => setSidebarOpen(false)}
                className={cn(
                  "ov-navigation-link flex items-center gap-3",
                  isActive
                    ? "ov-navigation-link-active"
                    : "",
                )}
                data-ov-nav-active={isActive}
              >
                <Icon className="size-4" />
                <span className="ov-nav-label">{t(labelKey)}</span>
              </Link>
            )
          })}
          {mobileNavContent && (
            <div
              className="mt-3 border-t border-white/10 pt-3 xl:hidden"
              onClick={(event) => {
                const target = event.target as HTMLElement
                if (target.closest("button, a")) setSidebarOpen(false)
              }}
            >
              {mobileNavContent}
            </div>
          )}
          <div className="ov-recent">
            <p>Recent</p>
            <span>{chat("historyWillAppear")}</span>
          </div>
        </nav>

        {footerNavigation.map(({ href, labelKey, icon: Icon }) => (
          <Link key={href} href={href} onClick={() => setSidebarOpen(false)} className="ov-docs-link">
            <Icon className="size-4" /> <span className="ov-docs-label">{t(labelKey)}</span>
          </Link>
        ))}

        <div className="ov-user-menu">
          <DropdownMenu>
            <DropdownMenuTrigger
              render={<button type="button" className="ov-account-trigger" aria-label={t("user")} />}
            >
              <span className="ov-avatar">{initialsFrom(user?.fullName)}</span>
              <span className="ov-account-copy min-w-0 flex-1 text-left">
                <span className="block truncate text-sm font-bold">{user?.fullName ?? t("user")}</span>
                <span className="block truncate text-xs text-[#a9b3ab]">{user?.email ?? ""}</span>
              </span>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" side="top" className="ov-account-menu w-56">
              <DropdownMenuGroup>
                <DropdownMenuLabel className="text-[#a9b3ab]">{user?.email ?? t("user")}</DropdownMenuLabel>
                <DropdownMenuItem onClick={() => void handleLogout()} className="gap-2 text-[#f2f5f0]">
                  <LogOut className="size-4" />
                  {t("logout")}
                </DropdownMenuItem>
              </DropdownMenuGroup>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </aside>

      {sidebarOpen && (
        <button
          type="button"
          className="fixed inset-0 z-30 bg-black/50 xl:hidden"
          onClick={() => setSidebarOpen(false)}
          aria-label={t("closeNavigation")}
        />
      )}

      <div ref={mainAreaRef} className={cn("ov-app-content min-h-dvh transition-[margin] duration-[380ms]", sidebarCollapsed ? "xl:ml-[76px]" : "xl:ml-[260px]")}>
        <div className="ov-background-grid" aria-hidden="true" />
        <div className="ov-aurora" aria-hidden="true"><i /><i /><i /><i /></div>
        <div className="ov-spotlight" aria-hidden="true" />
        <header className="ov-topbar sticky top-0 z-20 flex items-center gap-3 px-4 sm:px-8">
          <button
            type="button"
            className="ov-theme-toggle ml-auto"
            onClick={() => setTheme((current) => current === "light" ? "dark" : "light")}
            aria-label={theme === "light" ? "Switch to dark mode" : "Switch to light mode"}
          >
            {theme === "light" ? <Moon className="size-4" /> : <Sun className="size-4" />}
            <span>{theme === "light" ? "Dark" : "Light"}</span>
          </button>
          <LanguageSwitcher className="ov-language-switcher" variant="dark" />
        </header>
        <main className={cn("ov-main p-4 sm:p-8", contentClassName)}>{children}</main>
      </div>
    </div>
  )
}

export function ProtectedAppShell({ children }: { children: ReactNode }) {
  const common = useTranslations("Common")
  const router = useRouter()
  const status = useTokenRehydration()

  useEffect(() => {
    if (status === "unauthenticated") {
      const destination = `${window.location.pathname}${window.location.search}${window.location.hash}`
      router.replace(withNext("/login", destination))
    }
  }, [router, status])

  if (status === "rehydrating") {
    return <AppLoadingScreen label={common("loading")} />
  }

  if (status === "unauthenticated") {
    return null
  }

  return <AppShell>{children}</AppShell>
}

/**
 * Admin boundary: same login gate as {@link ProtectedAppShell} plus an
 * `ORG_OWNER` requirement. Used by the `(admin)` route-group layout served at
 * `/admin/*` by the `frontend-admin` deployment (OIDC client `openvie-admin`).
 */
export function ProtectedAdminShell({ children }: { children: ReactNode }) {
  const common = useTranslations("Common")
  const navigation = useTranslations("Navigation")
  const router = useRouter()
  const status = useTokenRehydration()
  const user = useAuthStore((state) => state.user)

  useEffect(() => {
    if (status === "unauthenticated") {
      const destination = `${window.location.pathname}${window.location.search}${window.location.hash}`
      router.replace(withNext("/login", destination))
    }
  }, [router, status])

  if (status === "rehydrating") {
    return <AppLoadingScreen label={common("loading")} />
  }

  if (status === "unauthenticated") {
    return null
  }

  if (user?.orgRole !== "ORG_OWNER") {
    return (
      <AppShell>
        <div
          role="alert"
          className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800"
        >
          {navigation("adminOnly")}
        </div>
      </AppShell>
    )
  }

  return <AppShell>{children}</AppShell>
}
