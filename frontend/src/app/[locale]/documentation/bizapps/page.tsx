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

const pre =
  "overflow-x-auto rounded-lg border border-[var(--ov-docs-border)] bg-[var(--ov-docs-surface)] p-4 font-mono text-xs leading-6"

export default async function BizAppsDocumentationPage({ params }: PageProps) {
  const { locale } = await params
  const t = await getTranslations({ locale: locale as AppLocale, namespace: "DocumentationContent.bizapps" })
  const page = documentationPage("/documentation/bizapps", locale as AppLocale)
  const sections = Object.fromEntries(page.sections.map((section) => [section.id, section.title]))

  return (
    <DocArticle title={page.title} description={page.description}>
      <DocSection id="overview" title={sections.overview}>
        <p>{t("introBody")}</p>
        <p className="text-[var(--ov-docs-muted)]">{t("introStructure")}</p>
        <ParameterTable
          parameters={[
            { name: "kind / name / version", type: "identifier + labels", required: true, description: t("topIdentity") },
            { name: "storage", type: "jsonb | typed", required: false, description: t("topStorage") },
            { name: "rbac", type: "read/write/delete/approve/submit", required: false, description: t("topRbac") },
            { name: "entities", type: "1–20 × fields 1–40", required: true, description: t("topEntities") },
            { name: "blocks", type: "≤40", required: false, description: t("topBlocks") },
            { name: "ui", type: "views ≤20 + layout", required: false, description: t("topUi") },
            { name: "conversational", type: "triggers + description", required: false, description: t("topConversational") },
          ]}
        />
        <Callout type="tip" title={t("unknownKeysTitle")}>
          {t("unknownKeysBody")}
        </Callout>
      </DocSection>

      <DocSection id="marketplace-install" title={sections["marketplace-install"]}>
        <p>{t("installBody")}</p>
        <BulletList>
          <li>{t("installInstall")}</li>
          <li>{t("installUpgrade")}</li>
          <li>{t("installUninstall")}</li>
          <li>{t("installDrafts")}</li>
        </BulletList>
        <Callout type="note" title={t("templateSourcesTitle")}>
          {t("templateSourcesBody")}
        </Callout>
      </DocSection>

      <DocSection id="manifest" title={sections.manifest}>
        <div className="overflow-x-auto">
          <pre className={pre}>{t("manifestExampleCode")}</pre>
        </div>
        <p className="mt-3 text-[var(--ov-docs-muted)]">{t("manifestNotes")}</p>
        <BulletList>
          <li>{t("fieldNames")}</li>
          <li>{t("fieldTypes")}</li>
          <li>{t("fieldDates")}</li>
          <li>{t("fieldPii")}</li>
          <li>{t("fieldHooks")}</li>
        </BulletList>
      </DocSection>

      <DocSection id="runtime-views" title={sections["runtime-views"]}>
        <p>{t("viewsIntro")}</p>
        <BulletList>
          <li>{t("viewRegistry")}</li>
          <li>{t("viewForm")}</li>
          <li>{t("viewTimeline")}</li>
          <li>{t("viewReport")}</li>
          <li>{t("viewAssistant")}</li>
        </BulletList>
        <ParameterTable
          parameters={[
            { name: "record.status", type: "ACTIVE | ARCHIVED", required: false, description: t("recordStatus") },
            { name: "runtime.approvalStatus", type: "PENDING | APPROVED | REJECTED", required: false, description: t("recordApproval") },
          ]}
        />
        <Callout type="security" title={t("recordFenceTitle")}>
          {t("recordFenceBody")}
        </Callout>
      </DocSection>

      <DocSection id="reports-knowledge" title={sections["reports-knowledge"]}>
        <p>{t("reportsIntro")}</p>
        <BulletList>
          <li>{t("reportsSpec")}</li>
          <li>{t("reportsAdmin")}</li>
          <li>{t("reportsExport")}</li>
        </BulletList>
        <p className="mt-4 font-semibold">{t("knowledgeTitle")}</p>
        <p>{t("knowledgeBody")}</p>
        <ParameterTable
          parameters={[
            { name: "config.knowledgeBaseId", type: "UUID (install config)", required: false, description: t("knowledgeTarget") },
            { name: "BIZAPPS_PROJECTION_DEBOUNCE_MS", type: "ms, default 30000", required: false, description: t("knowledgeDebounce") },
          ]}
        />
        <Callout type="warning" title={t("coverageTitle")}>
          {t("coverageBody")}
        </Callout>
      </DocSection>

      <DocSection id="credential-vault" title={sections["credential-vault"]}>
        <p>{t("vaultBody")}</p>
        <ParameterTable
          parameters={[
            { name: "api_key", type: "secret", required: false, description: t("vaultApiKey") },
            { name: "bearer", type: "secret", required: false, description: t("vaultBearer") },
            { name: "basic", type: "user + secret", required: false, description: t("vaultBasic") },
            { name: "oauth2", type: "client_id:client_secret", required: false, description: t("vaultOauth2") },
            { name: "mqtt", type: "username:password", required: false, description: t("vaultMqtt") },
            { name: "hmac", type: "signing secret (secretRef)", required: false, description: t("vaultHmac") },
          ]}
        />
        <BulletList>
          <li>{t("vaultScoping")}</li>
          <li>{t("vaultPem")}</li>
          <li>{t("vaultEncKey")}</li>
        </BulletList>
        <RelatedLinks
          links={[
            { href: "/apps", title: t("appsTitle"), description: t("appsDescription") },
            {
              href: "/documentation/composer",
              title: t("composerLinkTitle"),
              description: t("composerLinkDescription"),
            },
          ]}
        />
      </DocSection>
    </DocArticle>
  )
}
