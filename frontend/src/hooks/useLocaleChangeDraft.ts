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

import { useCallback, useEffect, useRef } from "react";

export const BEFORE_LOCALE_CHANGE_EVENT = "openvie:before-locale-change";

const DRAFT_VERSION = 1;
const DRAFT_PREFIX = "openvie:locale-draft:";

type DraftEnvelope<T> = {
  version: typeof DRAFT_VERSION;
  value: T;
};

export function localeDraftStorageKey(scope: string) {
  return `${DRAFT_PREFIX}${scope}`;
}

export function useLocaleChangeDraft<T>(
  scope: string,
  value: T,
  restore: (draft: T) => void,
  enabled = true,
) {
  const key = localeDraftStorageKey(scope);
  const restoredKeyRef = useRef<string | null>(null);

  useEffect(() => {
    if (!enabled || restoredKeyRef.current === key) return;
    restoredKeyRef.current = key;
    try {
      const raw = window.sessionStorage.getItem(key);
      if (!raw) return;
      const envelope = JSON.parse(raw) as Partial<DraftEnvelope<T>>;
      if (envelope.version !== DRAFT_VERSION || !("value" in envelope)) {
        window.sessionStorage.removeItem(key);
        return;
      }
      restore(envelope.value as T);
    } catch {
      try {
        window.sessionStorage.removeItem(key);
      } catch {
        // Invalid drafts can be ignored when browser storage is unavailable.
      }
    }
  }, [enabled, key, restore]);

  useEffect(() => {
    if (!enabled) return;
    const preserve = () => {
      try {
        const envelope: DraftEnvelope<T> = { version: DRAFT_VERSION, value };
        window.sessionStorage.setItem(key, JSON.stringify(envelope));
      } catch {
        // Locale switching must still work when storage is unavailable or full.
      }
    };
    window.addEventListener(BEFORE_LOCALE_CHANGE_EVENT, preserve);
    return () => window.removeEventListener(BEFORE_LOCALE_CHANGE_EVENT, preserve);
  }, [enabled, key, value]);

  return useCallback(() => {
    restoredKeyRef.current = key;
    try {
      window.sessionStorage.removeItem(key);
    } catch {
      // Clearing a draft is best-effort when browser storage is unavailable.
    }
  }, [key]);
}
