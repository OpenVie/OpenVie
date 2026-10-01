"use client";

import { useEffect } from "react";
import { useLocale, useTranslations } from "next-intl";
import { usePathname } from "@/i18n/navigation";
import type { AppLocale } from "@/i18n/routing";
import { documentationPage } from "@/lib/documentation";

const PAGE_TITLE_KEYS = {
  "/": "chat",
  "/login": "login",
  "/check-login-email": "checkLoginEmail",
  "/verify-login": "verifyLogin",
  "/accept-invitation": "acceptInvitation",
  "/documents": "documents",
  "/users": "users",
} as const;

export function PageTitle() {
  const pathname = usePathname();
  const locale = useLocale() as AppLocale;
  const t = useTranslations("PageTitles");

  useEffect(() => {
    const normalizedPath = pathname !== "/" ? pathname.replace(/\/$/, "") : pathname;
    let pageTitle: string | undefined;
    if (normalizedPath.startsWith("/documents/")) pageTitle = t("documentDetails");
    else if (normalizedPath === "/documentation" || normalizedPath.startsWith("/documentation/")) pageTitle = documentationPage(normalizedPath, locale).title;
    else {
      const key = PAGE_TITLE_KEYS[normalizedPath as keyof typeof PAGE_TITLE_KEYS];
      if (key) pageTitle = t(key);
    }
    document.title = pageTitle ? `${pageTitle} | OpenVie` : "OpenVie";
  }, [locale, pathname, t]);

  return null;
}
