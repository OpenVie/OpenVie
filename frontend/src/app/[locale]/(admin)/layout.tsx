import { ProtectedAdminShell } from "@/components/app/AppShell"

/**
 * Admin surface layout: every page under `/admin/*` requires `ORG_OWNER`.
 * Served by the `frontend-admin` deployment (OIDC client `openvie-admin`).
 */
export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return <ProtectedAdminShell>{children}</ProtectedAdminShell>
}
