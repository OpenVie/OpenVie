import { ProtectedAdminShell } from "@/components/app/AppShell"

/**
 * Soft admin boundary (Keycloak-ready).
 *
 * Route groups do not affect URLs, so `/workspaces` and `/settings` keep
 * their paths — they only gain the `ORG_OWNER` layout guard. After the hard
 * split this group maps to the `admin:3001` surface (`openvie-admin` client).
 */
export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return <ProtectedAdminShell>{children}</ProtectedAdminShell>
}
