"use client";

import { useLocale, useTranslations } from "next-intl";
import { useTransition } from "react";
import { Check, Globe2 } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { usePathname, useRouter } from "@/i18n/navigation";
import type { AppLocale } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { BEFORE_LOCALE_CHANGE_EVENT } from "@/hooks/useLocaleChangeDraft";

type LanguageSwitcherProps = {
  className?: string;
  variant?: "light" | "dark";
  showName?: boolean;
};

const localeLabels: Record<AppLocale, "english" | "vietnamese"> = {
  en: "english",
  vi: "vietnamese",
};

export function LanguageSwitcher({
  className,
  variant = "light",
  showName = false,
}: LanguageSwitcherProps) {
  const locale = useLocale() as AppLocale;
  const pathname = usePathname();
  const router = useRouter();
  const t = useTranslations("Common");
  const [isPending, startTransition] = useTransition();

  function changeLocale(nextLocale: AppLocale) {
    if (nextLocale === locale) return;
    const query = window.location.search;
    const hash = window.location.hash;
    const destination = `${pathname}${query}${hash}`;
    window.dispatchEvent(new Event(BEFORE_LOCALE_CHANGE_EVENT));
    startTransition(() => router.replace(destination, { locale: nextLocale }));
  }

  const dark = variant === "dark";

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <button
            type="button"
            disabled={isPending}
            aria-label={t("language")}
            className={cn(
              "inline-flex h-9 items-center justify-center gap-2 rounded-md border px-2.5 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500",
              dark
                ? "border-slate-600 bg-slate-800 text-slate-100 hover:bg-slate-700"
                : "border-slate-200 bg-white/90 text-slate-700 hover:bg-slate-50",
              className,
            )}
          />
        }
      >
        <Globe2 className="size-4" aria-hidden="true" />
        <span>{showName ? t(localeLabels[locale]) : locale.toUpperCase()}</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-44">
        {(["en", "vi"] as const).map((option) => (
          <DropdownMenuItem
            key={option}
            onClick={() => changeLocale(option)}
            className="min-h-9 cursor-pointer justify-between px-2.5"
          >
            <span>{t(localeLabels[option])}</span>
            {option === locale ? <Check className="size-4 text-indigo-600" aria-hidden="true" /> : null}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
