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
import { AlertCircle, CheckCircle2, Eye, EyeOff, Loader2 } from "lucide-react";
import { registrationStatusApi, setupApi } from "@/lib/auth-api";
import { useRouter } from "@/i18n/navigation";
import { cn } from "@/lib/utils";

/**
 * One-time web setup: the first person to reach this page claims the
 * installation (organization + owner account + default workspace). It is
 * irreversible; afterwards the route 404s and login takes over.
 */
export default function SetupPage() {
  const t = useTranslations("Setup");
  const router = useRouter();
  const [apiError, setApiError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [checking, setChecking] = useState(true);
  const [showPassword, setShowPassword] = useState(false);

  // If the install is already claimed, send the visitor to login.
  useEffect(() => {
    registrationStatusApi()
      .then((status) => {
        if (!status.setupRequired) router.replace("/login");
      })
      .catch(() => undefined)
      .finally(() => setChecking(false));
  }, [router]);

  const schema = z
    .object({
      organizationName: z.string().min(1, t("validation.required")),
      fullName: z.string().min(1, t("validation.required")),
      email: z.string().email(t("validation.email")),
      password: z.string().min(12, t("validation.passwordLength")),
      confirmPassword: z.string().min(12, t("validation.passwordLength")),
      allowSelfRegistration: z.boolean(),
    })
    .refine((values) => values.password === values.confirmPassword, {
      path: ["confirmPassword"],
      message: t("validation.mismatch"),
    });
  type SetupForm = z.infer<typeof schema>;

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<SetupForm>({
    resolver: zodResolver(schema),
    defaultValues: { allowSelfRegistration: false },
  });

  const onSubmit = async (data: SetupForm) => {
    setApiError(null);
    try {
      await setupApi({
        organizationName: data.organizationName,
        fullName: data.fullName,
        email: data.email,
        password: data.password,
        allowSelfRegistration: data.allowSelfRegistration,
      });
      setDone(true);
      window.setTimeout(() => router.replace("/login"), 1200);
    } catch (cause) {
      setApiError(cause instanceof Error ? cause.message : t("fallback"));
    }
  };

  if (checking) {
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
        <h1>{t("title")}</h1>
        <p className="ov-auth-subtitle">{t("description")}</p>

        {done ? (
          <div className="ov-auth-success">
            <CheckCircle2 className="size-5" />
            {t("done")}
          </div>
        ) : (
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            <div className="space-y-1.5">
              <label htmlFor="organizationName" className="ov-auth-label">{t("organizationName")}</label>
              <input id="organizationName" disabled={isSubmitting} className="ov-auth-input" {...register("organizationName")} />
              {errors.organizationName && (
                <p className="text-red-400 text-xs">{errors.organizationName.message}</p>
              )}
            </div>
            <div className="space-y-1.5">
              <label htmlFor="fullName" className="ov-auth-label">{t("ownerName")}</label>
              <input id="fullName" disabled={isSubmitting} className="ov-auth-input" {...register("fullName")} />
              {errors.fullName && (
                <p className="text-red-400 text-xs">{errors.fullName.message}</p>
              )}
            </div>
            <div className="space-y-1.5">
              <label htmlFor="email" className="ov-auth-label">{t("ownerEmail")}</label>
              <input id="email" type="email" disabled={isSubmitting} className="ov-auth-input" {...register("email")} />
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
              {errors.password && (
                <p className="text-red-400 text-xs">{errors.password.message}</p>
              )}
            </div>
            <div className="space-y-1.5">
              <label htmlFor="confirmPassword" className="ov-auth-label">{t("confirmPassword")}</label>
              <input
                id="confirmPassword"
                type="password"
                disabled={isSubmitting}
                className="ov-auth-input"
                {...register("confirmPassword")}
              />
              {errors.confirmPassword && (
                <p className="text-red-400 text-xs">{errors.confirmPassword.message}</p>
              )}
            </div>
            <div className="flex items-center gap-2">
              <input
                id="allowSelfRegistration"
                type="checkbox"
                disabled={isSubmitting}
                className="ov-auth-checkbox"
                {...register("allowSelfRegistration")}
              />
              <label htmlFor="allowSelfRegistration" className="text-sm cursor-pointer text-[#a9b3ab]">
                {t("allowSelfRegistration")}
              </label>
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
                  {t("submitting")}
                </>
              ) : (
                t("submit")
              )}
            </button>
            <p className="text-xs text-[#8e9992] text-center">{t("irreversible")}</p>
          </form>
        )}
      </div>
    </>
  );
}
