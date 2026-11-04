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
  const page = documentationPage("/documentation/api-keys", locale as AppLocale)
  return { title: page.title, description: page.description }
}

export default async function ApiKeysDocumentationPage({ params }: PageProps) {
  const { locale } = await params
  const t = await getTranslations({ locale: locale as AppLocale, namespace: "DocumentationContent.apiKeys" })
  const page = documentationPage("/documentation/api-keys", locale as AppLocale)
  const sections = Object.fromEntries(page.sections.map((section) => [section.id, section.title]))

  return (
    <DocArticle title={page.title} description={page.description}>
      <DocSection id="when" title={sections.when}>
        <p>{t("whenBody")}</p>
        <Callout type="tip">
          {t("whenTip")}
        </Callout>
      </DocSection>

      <DocSection id="create" title={sections.create}>
        <p>{t("createBody")}</p>
        <DocFigure
          src="/docs/api-key-create.png"
          alt={t("createFigureAlt")}
          caption={t("createFigureCaption")}
        />
        <ParameterTable
          parameters={[
            {
              name: "name",
              type: "string",
              required: true,
              description: t("paramName"),
            },
            {
              name: "workspace_id",
              type: "UUID",
              required: true,
              description: t("paramWorkspace"),
            },
            {
              name: "role",
              type: "string",
              required: true,
              description: t("paramRole"),
            },
            {
              name: "expires_at",
              type: "ISO-8601",
              required: false,
              description: t("paramExpiry"),
            },
          ]}
        />
      </DocSection>

      <DocSection id="secret-once" title={sections["secret-once"]}>
        <p>{t("secretOnceBody")}</p>
        <DocFigure
          src="/docs/api-key-secret.png"
          alt={t("secretFigureAlt")}
          caption={t("secretFigureCaption")}
        />
        <Callout type="security">
          {t("secretSecurity")}
        </Callout>
      </DocSection>

      <DocSection id="manage" title={sections.manage}>
        <p>{t("manageBody")}</p>
        <DocFigure
          src="/docs/api-keys-table.png"
          alt={t("tableFigureAlt")}
          caption={t("tableFigureCaption")}
        />
        <Callout type="warning">
          {t("revokeWarning")}
        </Callout>
      </DocSection>

      <DocSection id="first-calls" title={sections["first-calls"]}>
        <p>{t("firstCallsBody")}</p>

        <div className="space-y-4">
          <div>
            <h3 className="font-semibold text-slate-900 dark:text-slate-100">{t("call1Title")}</h3>
            <pre className="mt-2 overflow-x-auto rounded-lg bg-slate-900 p-4 text-xs font-mono text-slate-100">
              <code>{`curl -X GET https://api.openvie.vn/api/v1/workspaces \\
  -H "Authorization: Bearer ovk_YOUR_SECRET_KEY"`}</code>
            </pre>
          </div>

          <div>
            <h3 className="font-semibold text-slate-900 dark:text-slate-100">{t("call2Title")}</h3>
            <pre className="mt-2 overflow-x-auto rounded-lg bg-slate-900 p-4 text-xs font-mono text-slate-100">
              <code>{`curl -X POST https://api.openvie.vn/api/v1/chat/sessions \\
  -H "Authorization: Bearer ovk_YOUR_SECRET_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{
    "workspace_id": "00000000-0000-0000-0000-000000000001",
    "title": "API Support Session"
  }'`}</code>
            </pre>
          </div>

          <div>
            <h3 className="font-semibold text-slate-900 dark:text-slate-100">{t("call3Title")}</h3>
            <pre className="mt-2 overflow-x-auto rounded-lg bg-slate-900 p-4 text-xs font-mono text-slate-100">
              <code>{`curl -X POST https://api.openvie.vn/api/v1/chat/sync \\
  -H "Authorization: Bearer ovk_YOUR_SECRET_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{
    "session_id": "YOUR_SESSION_ID",
    "content": "What is the policy for expense reimbursement?"
  }'`}</code>
            </pre>
          </div>
        </div>

        <RelatedLinks
          links={[
            {
              href: "/admin/organization/api-keys",
              title: t("apiKeysConsoleLink"),
              description: t("apiKeysConsoleDesc"),
            },
            {
              href: "/documentation/playground",
              title: t("playgroundLink"),
              description: t("playgroundDesc"),
            },
          ]}
        />
      </DocSection>
    </DocArticle>
  )
}
