"use client";

import { useState } from "react";
import Image from "next/image";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslations } from "next-intl";
import { AlertCircle, Eye, EyeOff, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
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
    <div className="min-h-screen flex items-center justify-center bg-slate-50 px-4 py-10">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="flex items-center justify-center gap-2 mb-2">
            <Image src="/logo.png" alt="" className="w-8 h-8" width={32} height={32} />
            <span className="text-2xl font-bold bg-gradient-to-r from-indigo-600 to-violet-600 bg-clip-text text-transparent">
              OpenVie
            </span>
          </div>
        </div>

        <Card className="shadow-md bg-white">
          <CardHeader>
            <CardTitle>{tc("title")}</CardTitle>
            <CardDescription>
              {forced ? tc("forcedDescription") : tc("description")}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
              <div className="space-y-1.5">
                <Label htmlFor="currentPassword">{tc("current")}</Label>
                <Input
                  id="currentPassword"
                  type="password"
                  autoComplete="current-password"
                  disabled={isSubmitting}
                  {...register("currentPassword", { onChange: () => setApiError(null) })}
                />
                {errors.currentPassword && (
                  <p className="text-red-600 text-xs">{errors.currentPassword.message}</p>
                )}
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="newPassword">{tc("new")}</Label>
                <div className="relative">
                  <Input
                    id="newPassword"
                    type={showPassword ? "text" : "password"}
                    autoComplete="new-password"
                    disabled={isSubmitting}
                    className="pr-10"
                    {...register("newPassword", { onChange: () => setApiError(null) })}
                  />
                  <button
                    type="button"
                    tabIndex={-1}
                    disabled={isSubmitting}
                    onClick={() => setShowPassword((v) => !v)}
                    className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-500 hover:text-slate-800 p-1 rounded-md disabled:opacity-50"
                    aria-label={showPassword ? t("hidePassword") : t("showPassword")}
                  >
                    {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
                {errors.newPassword && (
                  <p className="text-red-600 text-xs">{errors.newPassword.message}</p>
                )}
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="confirmPassword">{t("confirmPassword")}</Label>
                <Input
                  id="confirmPassword"
                  type="password"
                  autoComplete="new-password"
                  disabled={isSubmitting}
                  {...register("confirmPassword", { onChange: () => setApiError(null) })}
                />
                {errors.confirmPassword && (
                  <p className="text-red-600 text-xs">{errors.confirmPassword.message}</p>
                )}
              </div>

              {apiError && (
                <div
                  role="alert"
                  className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800"
                >
                  <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                  <p className="flex-1">{apiError}</p>
                </div>
              )}

              <Button
                type="submit"
                disabled={isSubmitting}
                className={cn(
                  "w-full h-10 bg-indigo-600 hover:bg-indigo-700 text-white",
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
              </Button>
              {!forced && (
                <Button
                  type="button"
                  variant="outline"
                  className="w-full"
                  onClick={() => router.push("/")}
                >
                  {tc("cancel")}
                </Button>
              )}
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
