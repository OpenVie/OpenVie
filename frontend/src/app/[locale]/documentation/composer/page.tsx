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

import {
  BulletList,
  Callout,
  DocArticle,
  DocSection,
  RelatedLinks,
} from "@/components/documentation/DocumentationComponents"
import type { AppLocale } from "@/i18n/routing"
import { documentationPage } from "@/lib/documentation"

type PageProps = { params: Promise<{ locale: string }> }

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { locale } = await params
  const page = documentationPage("/documentation/composer", locale as AppLocale)
  return { title: page.title, description: page.description }
}

export default async function ComposerDocumentationPage({ params }: PageProps) {
  const { locale } = await params
  const t = await getTranslations({ locale: locale as AppLocale, namespace: "DocumentationContent.composer" })
  const page = documentationPage("/documentation/composer", locale as AppLocale)
  const sections = Object.fromEntries(page.sections.map((section) => [section.id, section.title]))

  return (
    <DocArticle title={page.title} description={page.description}>
      <DocSection id="canvas-editing" title={sections["canvas-editing"]}>
        <p>{t("canvasBody")}</p>
        <Callout type="tip" title="Automated Graph Layout">
          {t("autoLayoutTip")}
        </Callout>
      </DocSection>

      <DocSection id="blocks-entities" title={sections["blocks-entities"]}>
        <BulletList>
          <li>{t("entityNodeText")}</li>
          <li>{t("blockNodeText")}</li>
        </BulletList>
      </DocSection>

      <DocSection id="ai-drafting" title={sections["ai-drafting"]}>
        <p>{t("aiDraftingBody")}</p>
        <Callout type="note" title="Human Review Before Installation">
          {t("aiDraftingTip")}
        </Callout>
      </DocSection>

      <DocSection id="template-exchange" title={sections["template-exchange"]}>
        <p>{t("templateExchangeBody")}</p>
        <RelatedLinks
          links={[
            {
              href: "/admin/apps/compose",
              title: t("openComposerTitle"),
              description: t("openComposerDescription"),
            },
          ]}
        />
      </DocSection>
    </DocArticle>
  )
}
