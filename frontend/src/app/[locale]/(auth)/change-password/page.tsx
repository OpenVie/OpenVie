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

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslations } from "next-intl";
import { AlertCircle, Eye, EyeOff, Loader2 } from "lucide-react";
import { useAuthStore } from "@/components/providers/StoreProvider";
import { authApiErrorMessage, changePasswordApi, logoutApi } from "@/lib/auth-api";
import { useRouter } from "@/i18n/navigation";
import { cn } from "@/lib/utils";

/**
 * Self-service password change. Also the landing page after login when an
 * administrator set the initial password (forced change): the session token
 * stays valid, and completing this form clears the flag server-side.
 */
export default function ChangePasswordPage() {
  const t = useTranslations("Auth");
  const tc = useTranslations("ChangePassword");
  const router = useRouter();
  const user = useAuthStore((s) => s.user);
  const accessToken = useAuthStore((s) => s.accessToken);
  const forced = Boolean(user?.mustChangePassword);
  const [apiError, setApiError] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);

  const schema = z
    .object({
      currentPassword: z.string().min(1, t("validation.passwordRequired")),
      newPassword: z.string().min(12, tc("validation.length")),
      confirmPassword: z.string(),
    })
    .refine((values) => values.newPassword === values.confirmPassword, {
      path: ["confirmPassword"],
      message: t("validation.passwordsMismatch"),
    })
    .refine((values) => values.newPassword !== values.currentPassword, {
      path: ["newPassword"],
      message: tc("validation.sameAsCurrent"),
    });
  type FormValues = z.infer<typeof schema>;

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ resolver: zodResolver(schema) });

  const onSubmit = async (values: FormValues) => {
    setApiError(null);
    try {
      await changePasswordApi(accessToken ?? "", {
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      });
      // The server-side flag is cleared; re-login picks up a clean session.
      await logoutApi().catch(() => undefined);
      router.replace("/login");
    } catch (cause) {
      setApiError(authApiErrorMessage(cause, tc("fallback")));
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

      <div className="ov-auth-card" style={{ marginTop: 24 }}>
        <h1>{tc("title")}</h1>
        <p className="ov-auth-subtitle">
          {forced ? tc("forcedDescription") : tc("description")}
        </p>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div className="space-y-1.5">
            <label htmlFor="currentPassword" className="ov-auth-label">{tc("current")}</label>
            <input
              id="currentPassword"
              type="password"
              autoComplete="current-password"
              disabled={isSubmitting}
              className="ov-auth-input"
              {...register("currentPassword", { onChange: () => setApiError(null) })}
            />
            {errors.currentPassword && (
              <p className="text-red-400 text-xs">{errors.currentPassword.message}</p>
            )}
          </div>
          <div className="space-y-1.5">
            <label htmlFor="newPassword" className="ov-auth-label">{tc("new")}</label>
            <div className="relative">
              <input
                id="newPassword"
                type={showPassword ? "text" : "password"}
                autoComplete="new-password"
                disabled={isSubmitting}
                className="ov-auth-input pr-10"
                {...register("newPassword", { onChange: () => setApiError(null) })}
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
            {errors.newPassword && (
              <p className="text-red-400 text-xs">{errors.newPassword.message}</p>
            )}
          </div>
          <div className="space-y-1.5">
            <label htmlFor="confirmPassword" className="ov-auth-label">{t("confirmPassword")}</label>
            <input
              id="confirmPassword"
              type="password"
              autoComplete="new-password"
              disabled={isSubmitting}
              className="ov-auth-input"
              {...register("confirmPassword", { onChange: () => setApiError(null) })}
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
                {tc("submitting")}
              </>
            ) : (
              tc("submit")
            )}
          </button>
          {!forced && (
            <button
              type="button"
              className="ov-auth-btn-outline"
              onClick={() => router.push("/")}
            >
              {tc("cancel")}
            </button>
          )}
        </form>
      </div>
    </>
  );
}
