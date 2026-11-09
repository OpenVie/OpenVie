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
import { DocFigure } from "@/components/documentation/DocumentationFigure"
import type { AppLocale } from "@/i18n/routing"
import { documentationPage } from "@/lib/documentation"

type PageProps = { params: Promise<{ locale: string }> }

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { locale } = await params
  const page = documentationPage("/documentation/workflow", locale as AppLocale)
  return { title: page.title, description: page.description }
}

export default async function WorkflowDocumentationPage({ params }: PageProps) {
  const { locale } = await params
  const t = await getTranslations({ locale: locale as AppLocale, namespace: "DocumentationContent.workflow" })
  const page = documentationPage("/documentation/workflow", locale as AppLocale)
  const sections = Object.fromEntries(page.sections.map((section) => [section.id, section.title]))

  return (
    <DocArticle title={page.title} description={page.description}>
      <DocSection id="approval-levels" title={sections["approval-levels"]}>
        <p>{t("twoLevelBody")}</p>
        <DocFigure
          src={`/docs/approval-flow-${locale}.svg`}
          alt={t("approvalFlowAlt")}
          caption={t("approvalFlowCaption")}
        />
        <Callout type="tip" title={t("twoLevelTitle")}>
          {t("sensitivePolicyTip")}
        </Callout>
      </DocSection>

      <DocSection id="task-inbox" title={sections["task-inbox"]}>
        <p>{t("claimBody")}</p>
        <BulletList>
          <li>
            <strong>Approve:</strong> Marks document or record approved and triggers downstream publication delegates or webhooks.
          </li>
          <li>
            <strong>Reject:</strong> Rejects submission back to creator with a mandatory review comment.
          </li>
          <li>
            <strong>Escalate:</strong> Hands off a pending Level 1 task to the Organization Owner for executive review.
          </li>
        </BulletList>
      </DocSection>

      <DocSection id="sla-timers" title={sections["sla-timers"]}>
        <div className="space-y-3">
          <p>{t("slaReminderText")}</p>
          <p>{t("slaEscalationText")}</p>
        </div>
        <Callout type="note" title={t("slaTitle")}>
          SLA configurations are defined per-organization and capped at 14 days maximum.
        </Callout>
      </DocSection>

      <DocSection id="role-permissions" title={sections["role-permissions"]}>
        <ParameterTable
          parameters={[
            {
              name: t("memberRoleTitle"),
              type: "MEMBER",
              description: t("memberRoleText"),
            },
            {
              name: t("adminRoleTitle"),
              type: "WORKSPACE_ADMIN",
              description: t("adminRoleText"),
            },
            {
              name: t("ownerRoleTitle"),
              type: "ORG_OWNER",
              description: t("ownerRoleText"),
            },
          ]}
        />
        <RelatedLinks
          links={[
            {
              href: "/approvals",
              title: t("approvalInboxTitle"),
              description: t("approvalInboxDescription"),
            },
          ]}
        />
      </DocSection>
    </DocArticle>
  )
}
