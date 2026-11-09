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
  const page = documentationPage("/documentation/settings", locale as AppLocale)
  return { title: page.title, description: page.description }
}

export default async function SettingsDocumentationPage({ params }: PageProps) {
  const { locale } = await params
  const t = await getTranslations({ locale: locale as AppLocale, namespace: "DocumentationContent.settings" })
  const page = documentationPage("/documentation/settings", locale as AppLocale)
  const sections = Object.fromEntries(page.sections.map((section) => [section.id, section.title]))

  return (
    <DocArticle title={page.title} description={page.description}>
      <DocSection id="organization" title={sections.organization}>
        <p>{t("orgProfileBody")}</p>
        <Callout type="tip">
          {t("orgProfileTip")}
        </Callout>
      </DocSection>

      <DocSection id="self-registration" title={sections["self-registration"]}>
        <p>{t("selfRegBody")}</p>
        <BulletList>
          <li>{t("selfRegEnabled")}</li>
          <li>{t("selfRegDisabled")}</li>
        </BulletList>
        <Callout type="security">
          {t("selfRegSecurity")}
        </Callout>
      </DocSection>

      <DocSection id="email-channels" title={sections["email-channels"]}>
        <p>{t("emailChannelsBody")}</p>
        <BulletList>
          <li>{t("smtpFieldHost")}</li>
          <li>{t("smtpFieldAuth")}</li>
          <li>{t("smtpFieldFrom")}</li>
        </BulletList>
        <Callout type="tip">
          {t("testEmailTip")}
        </Callout>
        <RelatedLinks
          links={[
            {
              href: "/documentation/auth",
              title: t("authLink"),
              description: t("authDesc"),
            },
            {
              href: "/admin/settings",
              title: t("settingsConsoleLink"),
              description: t("settingsConsoleDesc"),
            },
          ]}
        />
      </DocSection>
    </DocArticle>
  )
}
