import { LanguageSwitcher } from "@/components/i18n/LanguageSwitcher"

export default function AuthLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <div className="relative min-h-screen">
      <div className="fixed right-4 top-4 z-50">
        <LanguageSwitcher />
      </div>
      {children}
    </div>
  )
}
