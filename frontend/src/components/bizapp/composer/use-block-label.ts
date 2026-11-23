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

import { useTranslations } from "next-intl";
import type { BizAppBlock } from "@/types";
import { blockNameKey, blockSummary } from "@/lib/bizapp-block";

/** Footnote labels for arbitrary block parameters, with a raw-key fallback. */
export function useParamLabel() {
  const t = useTranslations("BizApps.composer");
  type MessageKey = Parameters<typeof t>[0];

  return (key: string): string | null => {
    const messageKey = `inspector.params.${key}` as MessageKey;
    return t.has(messageKey) ? t(messageKey) : null;
  };
}
/** Shared composer labels so nodes, lists and inspectors agree on blocks. */
export function useBlockLabel() {
  const t = useTranslations("BizApps.composer");
  type MessageKey = Parameters<typeof t>[0];

  return {
    name(type: string): string {
      const key = blockNameKey(type);
      if (key) {
        const messageKey = key as MessageKey;
        if (t.has(messageKey)) return t(messageKey);
      }
      return type.replace(/[_-]+/g, " ");
    },
    summary(block: BizAppBlock): string {
      const message = blockSummary(block);
      return t(message.key as MessageKey, message.values);
    },
  };
}
