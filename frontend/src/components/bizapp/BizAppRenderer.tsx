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

"use client";

import { useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";

import type { BizAppInstalledAppView } from "@/types";
import { BizAppRegistryView } from "@/components/bizapp/BizAppRegistryView";
import { BizAppReportView } from "@/components/bizapp/BizAppReportView";
import { BizAppTimelineView } from "@/components/bizapp/BizAppTimelineView";
import { BizAppAssistantView } from "@/components/bizapp/BizAppAssistantView";

type UiView = { prefix: string; target: string; raw: string };
/**
 * Manifest-driven runtime surface of one installed app: walks `ui.views` and
 * renders the matching native view. Unknown prefixes (e.g. `assistant:*`) are
 * skipped with a visible note instead of breaking the whole app.
 */
export function BizAppRenderer({ app, admin = false }: { app: BizAppInstalledAppView; admin?: boolean }) {
  const t = useTranslations("BizApps");
  const locale = useLocale();
  const views = useMemo<UiView[]>(
    () =>
      (app.manifest.ui?.views ?? [])
        .map((raw) => {
          const [prefix, target] = raw.split(":", 2);
          return { prefix, target: target ?? "", raw };
        })
        .filter((view) => view.prefix && view.target),
    [app.manifest.ui?.views],
  );

  const [active, setActive] = useState<string>(views[0]?.raw ?? "");
  const current = views.find((view) => view.raw === active) ?? views[0];

  const label = (view: UiView): string => {
    if (view.prefix === "report") {
      return t("nav.report", { view: view.target });
    }
    if (view.prefix === "assistant") {
      return t("nav.assistant", { fallback: "Trợ lý AI" });
    }
    const entity = app.manifest.entities.find((candidate) => candidate.name === view.target);
    return entity?.label
      ? entityLabel(entity, locale) ?? view.target
      : view.target;
  };

  if (views.length === 0) {
    return <p className="text-sm text-muted-foreground">{t("noViews")}</p>;
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2" role="tablist" aria-label={t("viewsNav")}>
        {views.map((view) => (
          <button
            key={view.raw}
            role="tab"
            aria-selected={current?.raw === view.raw}
            onClick={() => setActive(view.raw)}
            className={
              "rounded-full border px-4 py-1.5 text-sm transition-colors " +
              (current?.raw === view.raw
                ? "border-primary bg-primary text-primary-foreground"
                : "bg-background hover:bg-muted")
            }
          >
            {label(view)}
          </button>
        ))}
      </div>

      {current && current.prefix === "registry" && <BizAppRegistryView app={app} entityName={current.target} />}
      {current && current.prefix === "timeline" && <BizAppTimelineView app={app} entityName={current.target} />}
      {current && current.prefix === "form" && <BizAppRegistryView app={app} entityName={current.target} openCreate />}
      {current && current.prefix === "report" && (
        <BizAppReportView app={app} viewName={current.target} admin={admin} />
      )}
      {current && current.prefix === "assistant" && (
        <BizAppAssistantView app={app} blockId={current.target} />
      )}
    </div>
  );
}

function entityLabel(entity: { label?: { vi?: string | null; en?: string | null } | null }, locale: string): string | null {
  return (locale === "vi" ? entity.label?.vi : entity.label?.en) || null;
}
