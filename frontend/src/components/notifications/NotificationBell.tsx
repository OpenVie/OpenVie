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

"use client";

import { useEffect, useState, useCallback, useMemo } from "react";
import { Bell, Calendar, CheckCheck, ExternalLink, FileCheck, Mail, AlertTriangle, FileText } from "lucide-react";
import { useTranslations, useLocale } from "next-intl";
import { useRouter } from "@/i18n/navigation";
import { useApiClient } from "@/hooks/useApiClient";
import { useTheme } from "@/hooks/useTheme";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  listNotificationsApi,
  getUnreadNotificationsCountApi,
  markNotificationAsReadApi,
  markAllNotificationsAsReadApi,
} from "@/lib/notifications-api";
import { localizeNotification } from "@/lib/localize-notification";
import type { NotificationItem } from "@/types";
import { cn } from "@/lib/utils";

function renderTypeIcon(type: string) {
  switch (type) {
    case "BOOKING_REMINDER":
      return <Calendar className="size-3.5" />;
    case "WORKFLOW_DECIDED":
      return <FileCheck className="size-3.5" />;
    case "USER_INVITED":
      return <Mail className="size-3.5" />;
    case "DOCUMENT_FAILED":
      return <AlertTriangle className="size-3.5" />;
    default:
      return <FileText className="size-3.5" />;
  }
}

function badgeColorClass(type: string, dark: boolean) {
  switch (type) {
    case "BOOKING_REMINDER":
      return dark ? "bg-amber-500/15 text-amber-300" : "bg-amber-100 text-amber-800";
    case "WORKFLOW_DECIDED":
      return dark ? "bg-sky-500/15 text-sky-300" : "bg-sky-100 text-sky-800";
    case "USER_INVITED":
      return dark ? "bg-emerald-500/15 text-emerald-300" : "bg-emerald-100 text-emerald-800";
    case "DOCUMENT_FAILED":
      return dark ? "bg-rose-500/15 text-rose-300" : "bg-rose-100 text-rose-800";
    default:
      return dark ? "bg-cyan-500/15 text-cyan-300" : "bg-cyan-100 text-cyan-800";
  }
}

export function NotificationBell() {
  const t = useTranslations("Notifications");
  const locale = useLocale();
  const { request } = useApiClient();
  const { theme } = useTheme();
  const router = useRouter();

  const isDark = theme !== "light";

  const [open, setOpen] = useState(false);
  const [unreadCount, setUnreadCount] = useState(0);
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(false);

  const loadUnreadCount = useCallback(async () => {
    try {
      const data = await getUnreadNotificationsCountApi(request);
      setUnreadCount(data.unreadCount);
    } catch {
      // Background polling fails silently
    }
  }, [request]);

  const loadNotifications = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listNotificationsApi(request, 0, 10);
      setItems(data.content);
    } catch {
      // Ignore
    } finally {
      setLoading(false);
    }
  }, [request]);

  useEffect(() => {
    const initial = setTimeout(() => {
      void loadUnreadCount();
    }, 0);
    const interval = setInterval(() => {
      void loadUnreadCount();
    }, 30000);
    return () => {
      clearTimeout(initial);
      clearInterval(interval);
    };
  }, [loadUnreadCount]);

  useEffect(() => {
    if (!open) return;
    const initial = setTimeout(() => {
      void loadNotifications();
    }, 0);
    return () => clearTimeout(initial);
  }, [open, loadNotifications]);

  const handleMarkOne = async (item: NotificationItem) => {
    if (!item.read) {
      try {
        await markNotificationAsReadApi(request, item.id);
        setItems((prev) =>
          prev.map((i) => (i.id === item.id ? { ...i, read: true, readAt: new Date().toISOString() } : i))
        );
        setUnreadCount((c) => Math.max(0, c - 1));
      } catch {
        // Ignore
      }
    }
    if (item.link) {
      setOpen(false);
      router.push(item.link);
    }
  };

  const handleMarkAll = async () => {
    try {
      await markAllNotificationsAsReadApi(request);
      setItems((prev) =>
        prev.map((i) => ({ ...i, read: true, readAt: new Date().toISOString() }))
      );
      setUnreadCount(0);
    } catch {
      // Ignore
    }
  };

  // High contrast palette ensuring complete visibility in both themes
  const menuContainerStyle = useMemo(() => {
    if (isDark) {
      return {
        background: "#12161a",
        borderColor: "rgba(255, 255, 255, 0.14)",
        color: "#f2f5f0",
      };
    }
    return {
      background: "#ffffff",
      borderColor: "rgba(16, 24, 14, 0.14)",
      color: "#10150f",
    };
  }, [isDark]);

  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger
        render={
          <button
            type="button"
            className="ov-topbar-control relative cursor-pointer"
            aria-label={t("unreadCountAria", { count: unreadCount })}
          />
        }
      >
        <Bell className="size-3.5" />
        {unreadCount > 0 && (
          <span className="absolute -top-1 -right-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-red-500 px-1 text-[9px] font-bold text-white shadow-sm">
            {unreadCount > 99 ? "99+" : unreadCount}
          </span>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="end"
        sideOffset={8}
        className="w-80 sm:w-96 p-2 overflow-hidden rounded-xl shadow-2xl"
        style={menuContainerStyle}
      >
        {/* Header */}
        <div
          className="flex items-center justify-between px-2.5 py-2 mb-1"
          style={{
            borderBottom: isDark ? "1px solid rgba(255, 255, 255, 0.08)" : "1px solid rgba(16, 24, 14, 0.08)",
          }}
        >
          <span
            className="text-xs font-bold tracking-wide"
            style={{ color: isDark ? "#f2f5f0" : "#10150f" }}
          >
            {t("title")}
          </span>
          {unreadCount > 0 && (
            <button
              type="button"
              onClick={handleMarkAll}
              className="flex items-center gap-1 text-[11px] font-semibold text-[#1fb6ff] hover:underline cursor-pointer"
            >
              <CheckCheck className="size-3" />
              {t("markAllRead")}
            </button>
          )}
        </div>

        {/* List */}
        <div className="max-h-80 overflow-y-auto space-y-1 pr-0.5">
          {loading && items.length === 0 ? (
            <div
              className="p-6 text-center text-xs"
              style={{ color: isDark ? "#8e9992" : "#5c685f" }}
            >
              ...
            </div>
          ) : items.length === 0 ? (
            <div
              className="p-6 text-center text-xs font-medium"
              style={{ color: isDark ? "#8e9992" : "#5c685f" }}
            >
              {t("empty")}
            </div>
          ) : (
            items.map((item) => {
              const { title, message, badge } = localizeNotification(item, locale);
              return (
                <div
                  key={item.id}
                  onClick={() => void handleMarkOne(item)}
                  className="p-2.5 rounded-lg transition-colors cursor-pointer flex gap-3 items-start"
                  style={{
                    backgroundColor: !item.read
                      ? isDark
                        ? "rgba(31, 182, 255, 0.08)"
                        : "rgba(31, 182, 255, 0.09)"
                      : "transparent",
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.backgroundColor = !item.read
                      ? isDark ? "rgba(31, 182, 255, 0.14)" : "rgba(31, 182, 255, 0.16)"
                      : isDark ? "rgba(255, 255, 255, 0.05)" : "rgba(16, 24, 14, 0.05)";
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.backgroundColor = !item.read
                      ? isDark ? "rgba(31, 182, 255, 0.08)" : "rgba(31, 182, 255, 0.09)"
                      : "transparent";
                  }}
                >
                  {/* Left type icon */}
                  <div
                    className="mt-0.5 shrink-0 rounded-lg p-2"
                    style={{
                      backgroundColor: isDark ? "rgba(31, 182, 255, 0.12)" : "rgba(7, 133, 197, 0.1)",
                      color: isDark ? "#1fb6ff" : "#0785c5",
                    }}
                  >
                    {renderTypeIcon(item.type)}
                  </div>

                  {/* Body content */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-1.5">
                      <span
                        className="text-xs leading-snug truncate"
                        style={{
                          fontWeight: item.read ? 500 : 700,
                          color: isDark
                            ? item.read ? "#c7cfc8" : "#ffffff"
                            : item.read ? "#4b564e" : "#10150f",
                        }}
                      >
                        {title}
                      </span>
                      {!item.read && (
                        <span className="size-1.5 shrink-0 rounded-full bg-[#1fb6ff]" />
                      )}
                    </div>
                    <p
                      className="text-[11px] line-clamp-2 mt-0.5 leading-relaxed"
                      style={{
                        color: isDark
                          ? item.read ? "#718077" : "#a9b3ab"
                          : item.read ? "#5c685f" : "#2a352d",
                      }}
                    >
                      {message}
                    </p>
                    <div className="flex items-center gap-2 mt-1.5">
                      {badge && (
                        <span
                          className={cn(
                            "text-[9px] uppercase tracking-wider font-semibold px-2 py-0.5 rounded-full",
                            badgeColorClass(item.type, isDark)
                          )}
                        >
                          {badge}
                        </span>
                      )}
                      {item.link && (
                        <span
                          className="inline-flex items-center gap-0.5 text-[10px]"
                          style={{ color: isDark ? "#1fb6ff" : "#0785c5" }}
                        >
                          <ExternalLink className="size-2.5" />
                        </span>
                      )}
                    </div>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
