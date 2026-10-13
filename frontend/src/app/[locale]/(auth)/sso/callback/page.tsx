"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import Image from "next/image";
import { AlertCircle, Loader2 } from "lucide-react";
import { useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
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
    <div className="min-h-screen flex items-center justify-center bg-slate-50 px-4">
      <Card className="w-full max-w-md shadow-md bg-white">
        <CardHeader className="items-center text-center">
          <Image src="/logo.png" alt="" width={32} height={32} className="mb-2" />
          <CardTitle>{error ? t("sso.failedTitle") : t("sso.completingTitle")}</CardTitle>
          <CardDescription>
            {error ? t("sso.failedDescription") : t("sso.completingDescription")}
          </CardDescription>
        </CardHeader>
        <CardContent className="text-center">
          {error ? (
            <>
              <div role="alert" className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-left text-sm text-red-800">
                <AlertCircle className="mt-0.5 size-4 shrink-0" />
                <p>{error}</p>
              </div>
              <Link
                href="/login"
                className="mt-5 inline-flex h-10 w-full items-center justify-center rounded-md bg-indigo-600 px-4 text-sm font-medium text-white transition-colors hover:bg-indigo-700"
              >
                {t("sso.backToLogin")}
              </Link>
            </>
          ) : (
            <Loader2 className="mx-auto size-7 animate-spin text-indigo-600" aria-label={t("loading")} />
          )}
        </CardContent>
      </Card>
    </div>
  );
}

export default function SsoCallbackPage() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-slate-50" />}>
      <SsoCallbackContent />
    </Suspense>
  );
}
