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

import { useTranslations } from "next-intl";
import {
  BarChart3,
  Bell,
  BookOpen,
  Bot,
  CheckCircle2,
  Database,
  Globe,
  Send,
} from "lucide-react";
import { Button } from "@/components/ui/button";

interface PaletteProps {
  onAddEntity: () => void;
  onAddBlock: (type: string) => void;
}

const AVAILABLE_BLOCKS = [
  { type: "approval_workitem", label: "Approval", icon: CheckCircle2 },
  { type: "assistant", label: "Assistant", icon: Bot },
  { type: "knowledge_base", label: "Knowledge Base", icon: BookOpen },
  { type: "report", label: "Report", icon: BarChart3 },
  { type: "notify", label: "Notify", icon: Bell },
  { type: "webhook_out", label: "Webhook Out", icon: Send },
  { type: "external_call", label: "External Call", icon: Globe },
];

export function Palette({ onAddEntity, onAddBlock }: PaletteProps) {
  const t = useTranslations("BizApps.composer");

  return (
    <div className="w-56 border-r bg-muted/20 p-3 space-y-4 shrink-0 flex flex-col">
      <div>
        <h3 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">
          {t("addEntity")}
        </h3>
        <Button
          variant="outline"
          size="sm"
          onClick={onAddEntity}
          className="w-full justify-start gap-2 text-xs"
        >
          <Database className="h-4 w-4 text-primary" />
          <span>New Entity</span>
        </Button>
      </div>

      <div className="flex-1 space-y-1.5">
        <h3 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">
          {t("addBlock")}
        </h3>
        <div className="space-y-1">
          {AVAILABLE_BLOCKS.map((item) => {
            const Icon = item.icon;
            return (
              <Button
                key={item.type}
                variant="ghost"
                size="sm"
                onClick={() => onAddBlock(item.type)}
                className="w-full justify-start gap-2 text-xs hover:bg-muted font-normal"
              >
                <Icon className="h-4 w-4 text-muted-foreground" />
                <span>{item.label}</span>
              </Button>
            );
          })}
        </div>
      </div>
    </div>
  );
}
