import type { Metadata } from "next";
import { hasLocale, NextIntlClientProvider } from "next-intl";
import { getMessages, getTranslations, setRequestLocale } from "next-intl/server";
import { notFound } from "next/navigation";
import Script from "next/script";
import "../globals.css";
import { AppProviders } from "@/components/providers/AppProviders";
import { routing } from "@/i18n/routing";

type LocaleLayoutProps = {
  children: React.ReactNode;
  params: Promise<{ locale: string }>;
};

export function generateStaticParams() {
  return routing.locales.map((locale) => ({ locale }));
}

export async function generateMetadata({ params }: Omit<LocaleLayoutProps, "children">): Promise<Metadata> {
  const { locale } = await params;
  if (!hasLocale(routing.locales, locale)) return {};

  const t = await getTranslations({ locale, namespace: "Metadata" });
  return {
    title: {
      default: t("title"),
      template: `%s | ${t("productName")}`,
    },
    description: t("description"),
  };
}

export default async function LocaleLayout({ children, params }: LocaleLayoutProps) {
  const { locale } = await params;
  if (!hasLocale(routing.locales, locale)) notFound();

  setRequestLocale(locale);
  const messages = await getMessages();

  return (
    <html lang={locale} suppressHydrationWarning>
      <head>
        <Script id="openvie-theme" strategy="beforeInteractive">
          {`try {
            const theme = localStorage.getItem('openvie.theme');
            if (theme === 'light' || theme === 'dark') {
              document.documentElement.dataset.ovTheme = theme;
              document.documentElement.dataset.theme = theme;
              const applyTheme = () => {
                const shell = document.querySelector('.ov-shell');
                if (!shell) return false;
                shell.classList.toggle('ov-light', theme === 'light');
                return true;
              };
              if (!applyTheme()) {
                const observer = new MutationObserver(() => {
                  if (applyTheme()) observer.disconnect();
                });
                observer.observe(document.documentElement, { childList: true, subtree: true });
              }
            }
          } catch {}`}
        </Script>
      </head>
      <body className="font-sans" suppressHydrationWarning>
        <NextIntlClientProvider messages={messages}>
          <AppProviders>{children}</AppProviders>
        </NextIntlClientProvider>
      </body>
    </html>
  );
}
