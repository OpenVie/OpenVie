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

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import type { BizAppManifest, BizAppValidationIssue } from "@/types";
import { validateBizAppManifestApi } from "@/lib/bizapp-api";
import { useApiClient } from "@/hooks/useApiClient";

/** Debounced server validation plus the structured issues used by node badges. */
export function useManifestValidation(manifest: BizAppManifest) {
  const t = useTranslations("BizApps.composer");
  const { request } = useApiClient();
  const [isValidating, setIsValidating] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);
  const [issues, setIssues] = useState<BizAppValidationIssue[]>([]);
  const [isValid, setIsValid] = useState(true);

  useEffect(() => {
    let active = true;
    const timer = setTimeout(async () => {
      setIsValidating(true);
      try {
        const data = await validateBizAppManifestApi(request, manifest);
        if (!active) return;
        setIsValid(Boolean(data.ok));
        setErrors(Array.isArray(data.errors) ? data.errors : []);
        setIssues(Array.isArray(data.issues) ? data.issues : []);
      } catch (error) {
        if (!active) return;
        setIsValid(false);
        setErrors([error instanceof Error ? error.message : t("validationFailed")]);
        setIssues([]);
      } finally {
        if (active) setIsValidating(false);
      }
    }, 400);

    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [manifest, request, t]);

  return { isValid, isValidating, errors, issues };
}
