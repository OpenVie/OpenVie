// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import type { Metadata } from "next"
import { getTranslations } from "next-intl/server"

import { Callout, DocArticle, DocSection, RelatedLinks, Step, Steps } from "@/components/documentation/DocumentationComponents"
import { Link } from "@/i18n/navigation"
import type { AppLocale } from "@/i18n/routing"
import { documentationPage } from "@/lib/documentation"

type PageProps = { params: Promise<{ locale: string }> }

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { locale } = await params
  const page = documentationPage("/documentation/getting-started", locale as AppLocale)
  return { title: page.title, description: page.description }
}

export default async function GettingStartedPage({ params }: PageProps) {
  const { locale } = await params
  const t = await getTranslations({ locale: locale as AppLocale, namespace: "DocumentationContent.gettingStarted" })
  const page = documentationPage("/documentation/getting-started", locale as AppLocale)
  const sections = Object.fromEntries(page.sections.map((section) => [section.id, section.title]))

  return <DocArticle title={page.title} description={page.description}>
    <Steps>
      <Step title={t("signInTitle")}>{t("signInText")}</Step>
      <Step title={t("uploadTitle")}>{t("uploadText")}</Step>
      <Step title={t("indexTitle")}>{t("indexText")}</Step>
      <Step title={t("askTitle")}>{t("askText")}</Step>
    </Steps>
    <DocSection id="upload" title={sections.upload}>
      <p>{t.rich("uploadBody", { link: (chunks) => <Link className="text-primary underline" href="/documents">{chunks}</Link>, strong: (chunks) => <strong>{chunks}</strong> })}</p>
      <Callout type="warning">{t.rich("uploadWarning", { code: (chunks) => <code>{chunks}</code> })}</Callout>
    </DocSection>
    <DocSection id="index" title={sections.index}>
      <p>{t.rich("indexBody", { strong: (chunks) => <strong>{chunks}</strong> })}</p>
    </DocSection>
    <DocSection id="test" title={sections.test}>
      <p>{t("testBody")}</p>
      <Callout type="tip">{t("testTip")}</Callout>
    </DocSection>
    <DocSection id="next-step" title={sections["next-step"]}>
      <RelatedLinks links={[{ href: "/documentation/documents", title: t("documentsTitle"), description: t("documentsDescription") }, { href: "/documentation/playground", title: t("playgroundTitle"), description: t("playgroundDescription") }]} />
    </DocSection>
  </DocArticle>
}
