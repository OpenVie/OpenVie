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

import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslations } from "next-intl";
import { Link, useRouter } from "@/i18n/navigation";
import { AlertCircle, Eye, EyeOff, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/components/providers/StoreProvider";
import {
  authApiErrorMessage,
  registrationStatusApi,
  registerApi,
} from "@/lib/auth-api";

type RegisterForm = {
  fullName: string;
  email: string;
  password: string;
  confirmPassword: string;
};

/**
 * Self-service signup. Only offered while the organization enables
 * self-registration; the API answers 409 otherwise, and this page sends
 * visitors to login when the toggle is off.
 */
export default function RegisterPage() {
  const t = useTranslations("Auth");
  const tr = useTranslations("Register");
  const router = useRouter();
  const setAuth = useAuthStore((s) => s.setAuth);
  const [apiError, setApiError] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);
  const [enabled, setEnabled] = useState<boolean | null>(null);

  useEffect(() => {
    registrationStatusApi()
      .then((status) => {
        if (!status.selfRegistrationAllowed || status.setupRequired) {
          router.replace("/login");
        } else {
          setEnabled(true);
        }
      })
      .catch(() => router.replace("/login"));
  }, [router]);

  const schema = z
    .object({
      fullName: z.string().trim().min(1, t("validation.enterFullName")).max(255),
      email: z.string().min(1, t("validation.emailRequired")).email(t("validation.invalidEmail")),
      password: z.string().min(12, t("validation.passwordLength")),
      confirmPassword: z.string(),
    })
    .refine((values) => values.password === values.confirmPassword, {
      path: ["confirmPassword"],
      message: t("validation.passwordsMismatch"),
    });

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<RegisterForm>({
    resolver: zodResolver(schema),
    defaultValues: { fullName: "", email: "", password: "", confirmPassword: "" },
  });

  const onSubmit = async (data: RegisterForm) => {
    setApiError(null);
    try {
      const res = await registerApi({
        fullName: data.fullName,
        email: data.email,
        password: data.password,
      });
      setAuth(res.user, res.accessToken);
      router.replace("/");
    } catch (cause) {
      setApiError(authApiErrorMessage(cause, tr("fallback")));
    }
  };

  if (enabled === null) {
    return (
      <div className="grid min-h-dvh place-items-center">
        <Loader2 className="size-8 animate-spin text-[#c8f53c]" />
      </div>
    );
  }

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
        <h1>{tr("title")}</h1>
        <p className="ov-auth-subtitle">{tr("description")}</p>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div className="space-y-1.5">
            <label htmlFor="fullName" className="ov-auth-label">{t("fullName")}</label>
            <input id="fullName" autoComplete="name" disabled={isSubmitting} className="ov-auth-input" {...register("fullName")} />
            {errors.fullName && <p className="text-red-400 text-xs">{errors.fullName.message}</p>}
          </div>
          <div className="space-y-1.5">
            <label htmlFor="email" className="ov-auth-label">{t("email")}</label>
            <input id="email" type="email" autoComplete="email" disabled={isSubmitting} className="ov-auth-input" {...register("email")} />
            {errors.email && <p className="text-red-400 text-xs">{errors.email.message}</p>}
          </div>
          <div className="space-y-1.5">
            <label htmlFor="password" className="ov-auth-label">{t("password")}</label>
            <div className="relative">
              <input
                id="password"
                type={showPassword ? "text" : "password"}
                autoComplete="new-password"
                disabled={isSubmitting}
                className="ov-auth-input pr-10"
                {...register("password")}
              />
              <button
                type="button"
                tabIndex={-1}
                disabled={isSubmitting}
                onClick={() => setShowPassword((v) => !v)}
                className="ov-auth-eye"
                aria-label={showPassword ? t("hidePassword") : t("showPassword")}
              >
                {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
            {errors.password && <p className="text-red-400 text-xs">{errors.password.message}</p>}
          </div>
          <div className="space-y-1.5">
            <label htmlFor="confirmPassword" className="ov-auth-label">{t("confirmPassword")}</label>
            <input
              id="confirmPassword"
              type="password"
              autoComplete="new-password"
              disabled={isSubmitting}
              className="ov-auth-input"
              {...register("confirmPassword")}
            />
            {errors.confirmPassword && (
              <p className="text-red-400 text-xs">{errors.confirmPassword.message}</p>
            )}
          </div>

          {apiError && (
            <div role="alert" className="ov-auth-error">
              <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
              <p className="flex-1">{apiError}</p>
            </div>
          )}

          <button
            type="submit"
            disabled={isSubmitting}
            className={cn(
              "ov-auth-btn",
              isSubmitting && "opacity-70",
            )}
          >
            {isSubmitting ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                {tr("submitting")}
              </>
            ) : (
              tr("submit")
            )}
          </button>
          <p className="text-center text-sm text-[#a9b3ab]">
            {tr("haveAccount")}{" "}
            <Link href="/login" className="ov-auth-link">
              {t("login.signIn")}
            </Link>
          </p>
        </form>
      </div>
    </>
  );
}
