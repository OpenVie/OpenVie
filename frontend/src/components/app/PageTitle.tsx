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

import { useEffect } from "react";
import { useLocale, useTranslations } from "next-intl";
import { usePathname } from "@/i18n/navigation";
import type { AppLocale } from "@/i18n/routing";
import { documentationPage } from "@/lib/documentation";

const PAGE_TITLE_KEYS = {
  "/": "chat",
  "/login": "login",
  "/register": "register",
  "/setup": "setup",
  "/change-password": "changePassword",
  "/accept-invitation": "acceptInvitation",
  "/documents": "documents",
  "/users": "users",
  "/admin/organization": "organization",
  "/admin/workspaces": "workspaces",
  "/admin/settings": "settings",
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
