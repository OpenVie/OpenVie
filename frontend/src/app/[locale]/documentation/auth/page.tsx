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
  const page = documentationPage("/documentation/auth", locale as AppLocale)
  return { title: page.title, description: page.description }
}

export default async function AuthDocumentationPage({ params }: PageProps) {
  const { locale } = await params
  const t = await getTranslations({ locale: locale as AppLocale, namespace: "DocumentationContent.auth" })
  const page = documentationPage("/documentation/auth", locale as AppLocale)
  const sections = Object.fromEntries(page.sections.map((section) => [section.id, section.title]))

  return (
    <DocArticle title={page.title} description={page.description}>
      <DocSection id="password-sso" title={sections["password-sso"]}>
        <p>{t("passwordSsoBody")}</p>
        <Callout type="tip">
          {t("ssoTip")}
        </Callout>
      </DocSection>

      <DocSection id="first-login" title={sections["first-login"]}>
        <p>{t("firstLoginBody")}</p>
        <BulletList>
          <li>{t("passwordRequirement")}</li>
        </BulletList>
        <Callout type="security">
          {t("securityTip")}
        </Callout>
      </DocSection>

      <DocSection id="switch-workspace" title={sections["switch-workspace"]}>
        <p>{t("switchWorkspaceBody")}</p>
        <p>{t("languageBody")}</p>
        <Callout type="note">
          {t("workspaceDataNote")}
        </Callout>
      </DocSection>

      <DocSection id="recover-access" title={sections["recover-access"]}>
        <p>{t("recoverAccessBody")}</p>
        <Callout type="note">
          {t("ssoRecoveryNote")}
        </Callout>
        <RelatedLinks
          links={[
            {
              href: "/documentation/members",
              title: t("memberDocLink"),
              description: t("memberDocDesc"),
            },
            {
              href: "/login",
              title: t("loginLink"),
              description: t("loginDesc"),
            },
          ]}
        />
      </DocSection>
    </DocArticle>
  )
}
