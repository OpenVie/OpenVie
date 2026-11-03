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
  ParameterTable,
  RelatedLinks,
} from "@/components/documentation/DocumentationComponents"
import type { AppLocale } from "@/i18n/routing"
import { documentationPage } from "@/lib/documentation"

type PageProps = { params: Promise<{ locale: string }> }

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { locale } = await params
  const page = documentationPage("/documentation/bizapps", locale as AppLocale)
  return { title: page.title, description: page.description }
}

export default async function BizAppsDocumentationPage({ params }: PageProps) {
  const { locale } = await params
  const t = await getTranslations({ locale: locale as AppLocale, namespace: "DocumentationContent.bizapps" })
  const page = documentationPage("/documentation/bizapps", locale as AppLocale)
  const sections = Object.fromEntries(page.sections.map((section) => [section.id, section.title]))

  return (
    <DocArticle title={page.title} description={page.description}>
      <DocSection id="overview" title={sections.overview}>
        <p>{t("introBody")}</p>
        <ParameterTable
          parameters={[
            {
              name: t("bundledCrmTitle"),
              type: "crm (typed SQL)",
              description: t("bundledCrmText"),
            },
            {
              name: t("bundledBookingTitle"),
              type: "booking (typed SQL)",
              description: t("bundledBookingText"),
            },
            {
              name: t("bundledSignatureTitle"),
              type: "signature-request",
              description: t("bundledSignatureText"),
            },
          ]}
        />
      </DocSection>

      <DocSection id="marketplace-install" title={sections["marketplace-install"]}>
        <p>{t("installBody")}</p>
        <Callout type="note" title="Frozen Manifest Contract">
          Installing an app freezes its manifest schema onto the installation row. Subsequent changes to template catalogs never mutate active running applications.
        </Callout>
      </DocSection>

      <DocSection id="runtime-views" title={sections["runtime-views"]}>
        <p>{t("viewsBody")}</p>
        <BulletList>
          <li><strong>{t("viewRegistry")}</strong></li>
          <li><strong>{t("viewForm")}</strong></li>
          <li><strong>{t("viewTimeline")}</strong></li>
          <li><strong>{t("viewReport")}</strong></li>
          <li><strong>{t("viewAssistant")}</strong></li>
        </BulletList>
      </DocSection>

      <DocSection id="credential-vault" title={sections["credential-vault"]}>
        <p>{t("vaultBody")}</p>
        <Callout type="warning" title="No Private Key Custody">
          {t("vaultWarning")}
        </Callout>
        <RelatedLinks
          links={[
            {
              href: "/apps",
              title: t("appsTitle"),
              description: t("appsDescription"),
            },
          ]}
        />
      </DocSection>
    </DocArticle>
  )
}
