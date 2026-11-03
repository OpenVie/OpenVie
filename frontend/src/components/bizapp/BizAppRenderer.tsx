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

import { BarChart3, Bot, History, List, PlusCircle } from "lucide-react";
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
    const eLabel = (locale === "vi" ? entity?.label?.vi : entity?.label?.en) || view.target;

    const prefixLabel =
      view.prefix === "registry"
        ? t("nav.registry")
        : view.prefix === "form"
        ? t("nav.form")
        : view.prefix === "timeline"
        ? t("nav.timeline")
        : view.prefix;

    const sameEntityViews = views.filter((v) => v.target === view.target);
    const hasMultipleEntities = (app.manifest.entities?.length ?? 0) > 1;

    if (sameEntityViews.length > 1) {
      if (hasMultipleEntities) {
        return `${eLabel} · ${prefixLabel}`;
      }
      return prefixLabel;
    }

    return eLabel;
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
              "flex items-center gap-1.5 rounded-full border px-4 py-1.5 text-sm font-medium transition-colors " +
              (current?.raw === view.raw
                ? "border-primary bg-primary text-primary-foreground shadow-xs"
                : "bg-background text-muted-foreground hover:bg-muted hover:text-foreground")
            }
          >
            {viewIcon(view.prefix)}
            <span>{label(view)}</span>
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


function viewIcon(prefix: string) {
  switch (prefix) {
    case "registry":
      return <List className="size-3.5 shrink-0" />;
    case "form":
      return <PlusCircle className="size-3.5 shrink-0" />;
    case "timeline":
      return <History className="size-3.5 shrink-0" />;
    case "report":
      return <BarChart3 className="size-3.5 shrink-0" />;
    case "assistant":
      return <Bot className="size-3.5 shrink-0" />;
    default:
      return null;
  }
}
