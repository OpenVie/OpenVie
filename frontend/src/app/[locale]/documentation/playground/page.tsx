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

import { BulletList, Callout, DocArticle, DocSection } from "@/components/documentation/DocumentationComponents"
import type { AppLocale } from "@/i18n/routing"
import { documentationPage } from "@/lib/documentation"

type PageProps = { params: Promise<{ locale: string }> }

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { locale } = await params
  const page = documentationPage("/documentation/playground", locale as AppLocale)
  return { title: page.title, description: page.description }
}

export default async function PlaygroundDocumentationPage({ params }: PageProps) {
  const { locale } = await params
  const t = await getTranslations({ locale: locale as AppLocale, namespace: "DocumentationContent.playground" })
  const page = documentationPage("/documentation/playground", locale as AppLocale)
  const sections = Object.fromEntries(page.sections.map((section) => [section.id, section.title]))

  return <DocArticle title={page.title} description={page.description}>
    <DocSection id="ask" title={sections.ask}>
      <p>{t.rich("askBody", { code: (chunks) => <code>{chunks}</code> })}</p>
      <Callout type="tip">{t("askTip")}</Callout>
    </DocSection>
    <DocSection id="sources" title={sections.sources}>
      <p>{t("sourcesBody")}</p>
      <Callout type="note">{t("sourcesNote")}</Callout>
    </DocSection>
    <DocSection id="history" title={sections.history}>
      <p>{t("historyBody")}</p>
      <BulletList><li>{t("historyRecent")}</li><li>{t("historyTitle")}</li><li>{t("historyPrivate")}</li></BulletList>
    </DocSection>
    <DocSection id="manage" title={sections.manage}>
      <p>{t("manageBody")}</p>
    </DocSection>
  </DocArticle>
}
