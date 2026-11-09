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
  Step,
  Steps,
} from "@/components/documentation/DocumentationComponents"
import type { AppLocale } from "@/i18n/routing"
import { documentationPage } from "@/lib/documentation"

type PageProps = { params: Promise<{ locale: string }> }

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { locale } = await params
  const page = documentationPage("/documentation/members", locale as AppLocale)
  return { title: page.title, description: page.description }
}

export default async function MembersDocumentationPage({ params }: PageProps) {
  const { locale } = await params
  const t = await getTranslations({ locale: locale as AppLocale, namespace: "DocumentationContent.members" })
  const page = documentationPage("/documentation/members", locale as AppLocale)
  const sections = Object.fromEntries(page.sections.map((section) => [section.id, section.title]))

  return (
    <DocArticle title={page.title} description={page.description}>
      <DocSection id="org-accounts" title={sections["org-accounts"]}>
        <p>{t("orgAccountsBody")}</p>
        <BulletList>
          <li>
            <strong>{t("roleOwnerTitle")}:</strong> {t("roleOwnerText")}
          </li>
          <li>
            <strong>{t("roleAdminTitle")}:</strong> {t("roleAdminText")}
          </li>
          <li>
            <strong>{t("roleMemberTitle")}:</strong> {t("roleMemberText")}
          </li>
        </BulletList>
      </DocSection>

      <DocSection id="create-account" title={sections["create-account"]}>
        <p>{t("createAccountBody")}</p>
        <Steps>
          <Step title={t("createStep1")}>
            <p className="text-sm">{t("createStep1")}</p>
          </Step>
          <Step title={t("createStep2")}>
            <p className="text-sm">{t("createStep2")}</p>
          </Step>
          <Step title={t("createStep3")}>
            <p className="text-sm">{t("createStep3")}</p>
          </Step>
        </Steps>
      </DocSection>

      <DocSection id="invite-workspace" title={sections["invite-workspace"]}>
        <p>{t("inviteWorkspaceBody")}</p>
        <Steps>
          <Step title={t("inviteStep1")}>
            <p className="text-sm">{t("inviteStep1")}</p>
          </Step>
          <Step title={t("inviteStep2")}>
            <p className="text-sm">{t("inviteStep2")}</p>
          </Step>
          <Step title={t("inviteStep3")}>
            <p className="text-sm">{t("inviteStep3")}</p>
          </Step>
        </Steps>
      </DocSection>

      <DocSection id="password-status" title={sections["password-status"]}>
        <p>{t("passwordStatusBody")}</p>
        <Callout type="warning">
          {t("deactivateWarning")}
        </Callout>
        <RelatedLinks
          links={[
            {
              href: "/admin/organization",
              title: t("adminOrgLink"),
              description: t("adminOrgDesc"),
            },
            {
              href: "/admin/workspaces",
              title: t("workspacesLink"),
              description: t("workspacesDesc"),
            },
          ]}
        />
      </DocSection>
    </DocArticle>
  )
}
