import type { Metadata } from "next"
import { getTranslations } from "next-intl/server"
import { FileUp, MessageSquare, Settings, Users } from "lucide-react"

import { Callout, DocArticle, DocSection, FeatureBadge, RelatedLinks } from "@/components/documentation/DocumentationComponents"
import { Link } from "@/i18n/navigation"
import type { AppLocale } from "@/i18n/routing"
import { documentationPage } from "@/lib/documentation"

type PageProps = { params: Promise<{ locale: string }> }

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { locale } = await params
  const page = documentationPage("/documentation", locale as AppLocale)
  return { title: page.title, description: page.description }
}

export default async function DocumentationOverviewPage({ params }: PageProps) {
  const { locale } = await params
  const t = await getTranslations({ locale: locale as AppLocale, namespace: "DocumentationContent.overview" })
  const page = documentationPage("/documentation", locale as AppLocale)
  const sections = Object.fromEntries(page.sections.map((section) => [section.id, section.title]))
  const quickstarts = [
    { icon: FileUp, title: t("quickstarts.firstUploadTitle"), text: t("quickstarts.firstUploadText"), href: "/documentation/getting-started" },
    { icon: Settings, title: t("quickstarts.documentsTitle"), text: t("quickstarts.documentsText"), href: "/documentation/documents" },
    { icon: MessageSquare, title: t("quickstarts.playgroundTitle"), text: t("quickstarts.playgroundText"), href: "/documentation/playground" },
    { icon: Users, title: t("quickstarts.teamTitle"), text: t("quickstarts.teamText"), href: "/users" },
  ]
  const concepts = [
    [t("workspaceTitle"), t("workspaceText")],
    [t("knowledgeBaseTitle"), t("knowledgeBaseText")],
    [t("chatbotTitle"), t("chatbotText")],
    [t("sessionTitle"), t("sessionText")],
  ]

  return <DocArticle title={page.title} description={page.description}>
    <DocSection id="core-concepts" title={sections["core-concepts"]}>
      <div className="grid gap-4 sm:grid-cols-2">
        {concepts.map(([title, text]) => <div key={title} className="rounded-lg border border-slate-200 p-4"><h3 className="font-semibold text-slate-950">{title}</h3><p className="mt-1 text-sm leading-6 text-slate-600">{text}</p></div>)}
      </div>
      <Callout type="note" title={t("citationsTitle")}>{t.rich("citationsText", { code: (chunks) => <code>{chunks}</code> })}</Callout>
    </DocSection>
    <DocSection id="quickstarts" title={sections.quickstarts}>
      <div className="grid gap-4 sm:grid-cols-2">
        {quickstarts.map(({ icon: Icon, title, text, href }) => <Link key={title} href={href} className="rounded-xl border border-slate-200 p-5 hover:border-indigo-300 hover:bg-indigo-50/30"><Icon className="size-5 text-indigo-600" /><h3 className="mt-3 font-semibold text-slate-950">{title}</h3><p className="mt-1 text-sm leading-6 text-slate-600">{text}</p></Link>)}
      </div>
    </DocSection>
    <DocSection id="start-paths" title={sections["start-paths"]}>
      <div className="space-y-4">
        <p><FeatureBadge>{t("employeeBadge")}</FeatureBadge> {t("employeePath")}</p>
        <p><FeatureBadge kind="admin">{t("adminBadge")}</FeatureBadge> {t("adminPath")}</p>
      </div>
      <RelatedLinks links={[{ href: "/documentation/documents", title: t("documentsTitle"), description: t("documentsDescription") }, { href: "/documentation/playground", title: t("playgroundTitle"), description: t("playgroundDescription") }]} />
    </DocSection>
  </DocArticle>
}
