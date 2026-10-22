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

import { Suspense, useEffect, useRef, useState } from "react";
import Image from "next/image";
import { AlertCircle, Loader2 } from "lucide-react";
import { useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { useAuthStore } from "@/components/providers/StoreProvider";
import { Link, useRouter } from "@/i18n/navigation";
import { authApiErrorMessage, ssoExchangeApi } from "@/lib/auth-api";
import { consumeAuthDestination } from "@/lib/auth-redirect";
import { consumeOidcTransaction } from "@/lib/oidc";

function ssoErrorMessage(error: unknown, fallback: string, unknownAccount: string): string {
  const message = authApiErrorMessage(error, fallback);
  return message.includes("SSO_UNKNOWN_EMAIL") ? unknownAccount : message;
}

function SsoCallbackContent() {
  const t = useTranslations("Auth");
  const router = useRouter();
  const searchParams = useSearchParams();
  const setAuth = useAuthStore((state) => state.setAuth);
  const transactionRef = useRef<ReturnType<typeof consumeOidcTransaction> | undefined>(undefined);
  const exchangePromiseRef = useRef<ReturnType<typeof ssoExchangeApi> | null>(null);
  const [error, setError] = useState<string | null>(null);

  const code = searchParams.get("code");
  const state = searchParams.get("state");
  const providerError = searchParams.get("error");

  useEffect(() => {
    let active = true;
    void (async () => {
      // Deferring local validation keeps the effect focused on the external
      // callback/exchange and avoids a synchronous render from the effect.
      await Promise.resolve();
      if (!active) return;

      if (transactionRef.current === undefined) {
        transactionRef.current = consumeOidcTransaction(state);
      }
      const transaction = transactionRef.current;
      if (!transaction) {
        setError(t("sso.invalidState"));
        return;
      }
      if (providerError) {
        setError(searchParams.get("error_description") || t("sso.providerFailed"));
        return;
      }
      if (!code) {
        setError(t("sso.missingCode"));
        return;
      }

      try {
        exchangePromiseRef.current ??= ssoExchangeApi({
          code,
          codeVerifier: transaction.codeVerifier,
          redirectUri: transaction.redirectUri,
        });
        const response = await exchangePromiseRef.current;
        if (!active) return;
        setAuth(response.user, response.accessToken);
        router.replace(response.user.mustChangePassword ? "/change-password" : consumeAuthDestination());
      } catch (exchangeError) {
        if (!active) return;
        setError(ssoErrorMessage(exchangeError, t("sso.exchangeFailed"), t("sso.unknownAccount")));
      }
    })();

    return () => {
      active = false;
    };
  }, [code, providerError, router, searchParams, setAuth, state, t]);

  return (
    <section className="ov-auth-card ov-sso-card" aria-live="polite">
      <header>
        <Image src="/images/openvie-logo.svg" alt="" width={42} height={42} className="ov-sso-logo" priority />
        <h1>{error ? t("sso.failedTitle") : t("sso.completingTitle")}</h1>
        <p className="ov-auth-subtitle">
          {error ? t("sso.failedDescription") : t("sso.completingDescription")}
        </p>
      </header>
      <div>
        {error ? (
          <>
            <div role="alert" className="ov-auth-error text-left">
              <AlertCircle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
              <p>{error}</p>
            </div>
            <Link
              href="/login"
              className="ov-auth-btn mt-5"
            >
              {t("sso.backToLogin")}
            </Link>
          </>
        ) : (
          <Loader2 className="mx-auto size-7 animate-spin text-primary" aria-label={t("loading")} />
        )}
      </div>
    </section>
  );
}

export default function SsoCallbackPage() {
  return (
    <Suspense fallback={null}>
      <SsoCallbackContent />
    </Suspense>
  );
}
