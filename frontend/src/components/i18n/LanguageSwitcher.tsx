"use client";

import { useLocale, useTranslations } from "next-intl";
import { useTransition, type CSSProperties } from "react";
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
  menuClassName?: string;
  menuStyle?: CSSProperties;
  variant?: "light" | "dark";
  showName?: boolean;
};

const localeLabels: Record<AppLocale, "english" | "vietnamese"> = {
  en: "english",
  vi: "vietnamese",
};

export function LanguageSwitcher({
  className,
  menuClassName,
  menuStyle,

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

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <button
            type="button"
            disabled={isPending}
            aria-label={t("language")}
            className={cn(
              "ov-language-trigger inline-flex h-auto items-center justify-center gap-1.5 rounded-md border-none bg-transparent px-2.5 text-sm font-medium transition-colors focus-visible:outline-none",
              className,
            )}
          />
        }
      >
        <Globe2 className="size-4" aria-hidden="true" />
        <span>{showName ? t(localeLabels[locale]) : locale.toUpperCase()}</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="end"
        sideOffset={8}
        className={cn("ov-language-menu w-44", menuClassName)}
        style={menuStyle}
      >
        {(["en", "vi"] as const).map((option) => (
          <DropdownMenuItem
            key={option}
            onClick={() => changeLocale(option)}
            className={cn(
              "min-h-9 cursor-pointer justify-between px-2.5",
              option === locale && "ov-language-menu-item-active",
            )}
          >
            <span>{t(localeLabels[option])}</span>
            {option === locale ? <Check className="size-4 text-primary" aria-hidden="true" /> : null}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
