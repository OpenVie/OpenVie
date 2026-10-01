import { ProtectedAppShell } from "@/components/app/AppShell"


export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  return <ProtectedAppShell>{children}</ProtectedAppShell>
}
