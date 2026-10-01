import { DocumentationShell } from "@/components/documentation/DocumentationShell"


export default function DocumentationLayout({ children }: { children: React.ReactNode }) {
  return <DocumentationShell>{children}</DocumentationShell>
}
