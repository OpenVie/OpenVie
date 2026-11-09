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
  const page = documentationPage("/documentation/workspaces", locale as AppLocale)
  return { title: page.title, description: page.description }
}

export default async function WorkspacesDocumentationPage({ params }: PageProps) {
  const { locale } = await params
  const t = await getTranslations({ locale: locale as AppLocale, namespace: "DocumentationContent.workspaces" })
  const page = documentationPage("/documentation/workspaces", locale as AppLocale)
  const sections = Object.fromEntries(page.sections.map((section) => [section.id, section.title]))

  return (
    <DocArticle title={page.title} description={page.description}>
      <DocSection id="default-workspace" title={sections["default-workspace"]}>
        <p>{t("defaultWorkspaceBody")}</p>
        <Callout type="note">
          {t("defaultWorkspaceNote")}
        </Callout>
      </DocSection>

      <DocSection id="visibility" title={sections.visibility}>
        <p>{t("visibilityBody")}</p>
        <BulletList>
          <li>
            <strong>{t("publicVisibilityTitle")}:</strong> {t("publicVisibilityText")}
          </li>
          <li>
            <strong>{t("privateVisibilityTitle")}:</strong> {t("privateVisibilityText")}
          </li>
        </BulletList>
      </DocSection>

      <DocSection id="lifecycle" title={sections.lifecycle}>
        <p>{t("lifecycleBody")}</p>
        <BulletList>
          <li>{t("lifecycleCreate")}</li>
          <li>{t("lifecycleArchive")}</li>
          <li>{t("lifecycleDelete")}</li>
        </BulletList>
        <Callout type="warning">
          {t("archiveWarning")}
        </Callout>
      </DocSection>

      <DocSection id="membership" title={sections.membership}>
        <p>{t("membershipBody")}</p>
        <BulletList>
          <li>{t("roleAdminNote")}</li>
          <li>{t("roleMemberNote")}</li>
        </BulletList>
        <RelatedLinks
          links={[
            {
              href: "/documentation/documents",
              title: t("documentsLink"),
              description: t("documentsDesc"),
            },
            {
              href: "/admin/workspaces",
              title: t("workspacesAdminLink"),
              description: t("workspacesAdminDesc"),
            },
          ]}
        />
      </DocSection>
    </DocArticle>
  )
}
