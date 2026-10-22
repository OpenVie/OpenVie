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

import { Suspense, useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslations } from "next-intl";
import { useSearchParams } from "next/navigation";
import { Link, useRouter } from "@/i18n/navigation";
import { AlertCircle, Eye, EyeOff, Loader2, LogIn, X } from "lucide-react";
import { useAuthStore } from "@/components/providers/StoreProvider";
import { authApiErrorMessage, loginApi, registrationStatusApi } from "@/lib/auth-api";
import { cn } from "@/lib/utils";
import { beginOidcAuthorization } from "@/lib/oidc";
import { routing } from "@/i18n/routing";
import {
  consumeAuthDestination,
  rememberAuthDestination,
  safeInternalPath,
} from "@/lib/auth-redirect";

type LoginForm = { email: string; password: string; rememberMe: boolean };

function LoginContent() {
  const t = useTranslations("Auth");
  const tNav = useTranslations("Navigation");
  const router = useRouter();
  const searchParams = useSearchParams();
  const next = safeInternalPath(searchParams.get("next"));
  const setAuth = useAuthStore((s) => s.setAuth);
  const [showPassword, setShowPassword] = useState(false);
  const [apiError, setApiError] = useState<string | null>(null);
  const [canSelfRegister, setCanSelfRegister] = useState<boolean | null>(null);
  const [isSsoRedirecting, setIsSsoRedirecting] = useState(false);
  const loginSchema = z.object({
    email: z.string().min(1, t("validation.emailRequired")).email(t("validation.invalidEmail")),
    password: z.string().min(1, t("validation.passwordRequired")),
    rememberMe: z.boolean(),
  });

  useEffect(() => {
    rememberAuthDestination(next);
  }, [next]);

  useEffect(() => {
    let active = true;
    registrationStatusApi()
      .then((status) => {
        if (active) setCanSelfRegister(!status.setupRequired && status.selfRegistrationAllowed);
      })
      .catch(() => {
        if (active) setCanSelfRegister(false);
      });
    return () => { active = false; };
  }, []);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginForm>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      email: "",
      password: "",
      rememberMe: false,
    },
  });

  const onSubmit = async (data: LoginForm) => {
    setApiError(null);
    try {
      const res = await loginApi({
        email: data.email,
        password: data.password,
        rememberMe: data.rememberMe,
      });
      setAuth(res.user, res.accessToken);
      // An administrator-set initial password must be replaced before the
      // member reaches the product.
      if (res.user.mustChangePassword) {
        router.push("/change-password");
        return;
      }
      router.push(consumeAuthDestination());
    } catch (e) {
      setApiError(authApiErrorMessage(e, t("fallback.invalidCredentials")));
    }
  };

  const startSsoLogin = async () => {
    setApiError(null);
    setIsSsoRedirecting(true);
    try {
      rememberAuthDestination(next);
      const localePrefix = document.documentElement.lang === routing.defaultLocale
        ? ""
        : `/${document.documentElement.lang}`;
      const redirectUri = `${window.location.origin}${localePrefix}/sso/callback`;
      window.location.assign(await beginOidcAuthorization(redirectUri));
    } catch (error) {
      setApiError(authApiErrorMessage(error, t("sso.startFailed")));
      setIsSsoRedirecting(false);
    }
  };

  return (
    <>
      <div className="ov-auth-brand">
        <div className="ov-auth-brand-mark">
          <svg width="26" height="26" viewBox="0 0 32 32" fill="none"><circle cx="16" cy="16" r="12" stroke="currentColor" strokeWidth="3" /><circle cx="16" cy="16" r="5" fill="currentColor" /></svg>
        </div>
        <span>OpenVie</span>
      </div>
      <p className="ov-auth-tagline">{t("tagline")}</p>

      <div className="ov-auth-card">
        <h1>{t("login.title")}</h1>
        <p className="ov-auth-subtitle">{t("login.description")}</p>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div className="space-y-1.5">
            <label htmlFor="email" className="ov-auth-label">{t("email")}</label>
            <input
              id="email"
              type="email"
              placeholder="you@company.com"
              disabled={isSubmitting}
              aria-invalid={!!errors.email}
              className="ov-auth-input"
              {...register("email", { onChange: () => setApiError(null) })}
            />
            {errors.email && (
              <p className="text-red-400 text-xs">{errors.email.message}</p>
            )}
          </div>

          <div className="space-y-1.5">
            <label htmlFor="password" className="ov-auth-label">{t("password")}</label>
            <div className="relative">
              <input
                id="password"
                type={showPassword ? "text" : "password"}
                placeholder="••••••••"
                disabled={isSubmitting}
                className="ov-auth-input pr-10"
                aria-invalid={!!errors.password}
                {...register("password", {
                  onChange: () => setApiError(null),
                })}
              />
              <button
                type="button"
                tabIndex={-1}
                disabled={isSubmitting}
                onClick={() => setShowPassword((v) => !v)}
                className="ov-auth-eye"
                aria-label={
                  showPassword ? t("hidePassword") : t("showPassword")
                }
              >
                {showPassword ? (
                  <EyeOff className="w-4 h-4" />
                ) : (
                  <Eye className="w-4 h-4" />
                )}
              </button>
            </div>
            {errors.password && (
              <p className="text-red-400 text-xs">
                {errors.password.message}
              </p>
            )}
          </div>

          <div className="flex items-center gap-2">
            <input
              id="rememberMe"
              type="checkbox"
              disabled={isSubmitting}
              className="ov-auth-checkbox"
              {...register("rememberMe", {
                onChange: () => setApiError(null),
              })}
            />
            <label
              htmlFor="rememberMe"
              className="text-sm cursor-pointer text-[#a9b3ab]"
            >
              {t("login.rememberMe")}
            </label>
          </div>

          {apiError && (
            <div role="alert" className="ov-auth-error">
              <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
              <p className="flex-1">{apiError}</p>
              <button
                type="button"
                onClick={() => setApiError(null)}
                className="text-red-400 hover:text-red-200 p-0.5 rounded"
                aria-label={t("dismissError")}
              >
                <X className="w-4 h-4" />
              </button>
            </div>
          )}

          <button
            type="submit"
            disabled={isSubmitting || isSsoRedirecting}
            className={cn(
              "ov-auth-btn",
              isSubmitting && "opacity-70",
            )}
          >
            {isSubmitting ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                {t("login.signingIn")}
              </>
            ) : (
              t("login.signIn")
            )}
          </button>
        </form>

        <div className="ov-auth-divider" aria-hidden="true">
          <span />
          {t("login.or")}
          <span />
        </div>

        <button
          type="button"
          disabled={isSubmitting || isSsoRedirecting}
          onClick={startSsoLogin}
          className="ov-auth-btn-outline"
        >
          {isSsoRedirecting ? (
            <>
              <Loader2 className="w-4 h-4 animate-spin" />
              {t("login.redirectingToSso")}
            </>
          ) : (
            <>
              <LogIn className="w-4 h-4" />
              {t("login.signInWithSso")}
            </>
          )}
        </button>

        <div className="ov-auth-footer">
          {canSelfRegister && (
            <p>
              {t("login.noAccount")}{" "}
              <Link href="/register" className="ov-auth-link">
                {t("login.createAccount")}
              </Link>
            </p>
          )}
          {canSelfRegister === false && <p>{t("login.provisioned")}</p>}
          <p>
            <Link href="/documentation" className="ov-auth-link">
              {tNav("documentation")}
            </Link>
          </p>
        </div>
      </div>
    </>
  );
}

export default function LoginPage() {
  return (
    <Suspense fallback={<div className="min-h-screen" />}>
      <LoginContent />
    </Suspense>
  );
}
