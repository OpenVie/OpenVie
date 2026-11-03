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
import { AlertCircle, CheckCircle2, ChevronDown, ChevronUp } from "lucide-react";
import { useState } from "react";

interface ValidationPanelProps {
  isValid: boolean;
  errors: string[];
  isValidating?: boolean;
}

export function ValidationPanel({ isValid, errors, isValidating }: ValidationPanelProps) {
  const t = useTranslations("BizApps.composer");
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="border-t bg-card text-xs">
      <div
        className="flex items-center justify-between px-4 py-2 cursor-pointer hover:bg-muted/40 transition-colors select-none"
        onClick={() => setExpanded(!expanded)}
      >
        <div className="flex items-center gap-2">
          {isValidating ? (
            <span className="h-2 w-2 rounded-full bg-amber-500 animate-pulse" />
          ) : isValid ? (
            <CheckCircle2 className="h-4 w-4 text-emerald-500" />
          ) : (
            <AlertCircle className="h-4 w-4 text-destructive" />
          )}
          <span className="font-medium">
            {isValidating
              ? t("validating")
              : isValid
              ? t("valid")
              : `${t("invalid")} (${errors.length})`}
          </span>
        </div>
        <button type="button" className="text-muted-foreground">
          {expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronUp className="h-4 w-4" />}
        </button>
      </div>

      {expanded && !isValid && errors.length > 0 && (
        <div className="px-4 pb-3 max-h-40 overflow-y-auto space-y-1 border-t bg-muted/10 pt-2">
          {errors.map((err, i) => (
            <div key={i} className="flex items-start gap-1.5 text-destructive text-[11px]">
              <span className="shrink-0">•</span>
              <span>{err}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
