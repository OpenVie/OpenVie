import type { Metadata } from "next"
import { getTranslations } from "next-intl/server"

import { BulletList, Callout, DocArticle, DocSection, ParameterTable } from "@/components/documentation/DocumentationComponents"
import type { AppLocale } from "@/i18n/routing"
import { documentationPage } from "@/lib/documentation"

type PageProps = { params: Promise<{ locale: string }> }

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { locale } = await params
  const page = documentationPage("/documentation/documents", locale as AppLocale)
  return { title: page.title, description: page.description }
}

export default async function DocumentsDocumentationPage({ params }: PageProps) {
  const { locale } = await params
  const t = await getTranslations({ locale: locale as AppLocale, namespace: "DocumentationContent.documents" })
  const page = documentationPage("/documentation/documents", locale as AppLocale)
  const sections = Object.fromEntries(page.sections.map((section) => [section.id, section.title]))

  return <DocArticle title={page.title} description={page.description}>
    <DocSection id="supported-files" title={sections["supported-files"]}>
      <ParameterTable parameters={[
        { name: "PDF", type: ".pdf", description: t("pdfDescription") },
        { name: "Word", type: ".docx", description: t("wordDescription") },
        { name: "Text", type: ".txt, .md, .markdown", description: t("textDescription") },
        { name: "HTML", type: ".html, .htm", description: t("htmlDescription") },
        { name: "Spreadsheet", type: ".xlsx, .csv", description: t("spreadsheetDescription") },
      ]} />
      <Callout type="warning" title={t("limitTitle")}>{t("limitText")}</Callout>
    </DocSection>
    <DocSection id="indexing" title={sections.indexing}>
      <BulletList>
        <li><strong>{t("pendingLabel")}:</strong> {t("pendingText")}</li>
        <li><strong>{t("processingLabel")}:</strong> {t("processingText")}</li>
        <li><strong>{t("completedLabel")}:</strong> {t("completedText")}</li>
        <li><strong>{t("failedLabel")}:</strong> {t("failedText")}</li>
      </BulletList>
    </DocSection>
    <DocSection id="viewer-citations" title={sections["viewer-citations"]}>
      <p>{t("viewerBody")}</p>
      <p>{t("viewerVerification")}</p>
    </DocSection>
    <DocSection id="manage" title={sections.manage}>
      <p>{t("manageBody")}</p>
      <Callout type="warning">{t("deleteWarning")}</Callout>
    </DocSection>
  </DocArticle>
}
