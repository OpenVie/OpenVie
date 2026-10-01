"use client";

import { useEffect, useState } from "react";
import Image from "next/image";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslations } from "next-intl";
import { AlertCircle, CheckCircle2, Eye, EyeOff, Loader2 } from "lucide-react";
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
      <div className="grid min-h-dvh place-items-center bg-slate-50">
        <Loader2 className="size-8 animate-spin text-indigo-600" />
      </div>
    );
  }

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
          <p className="text-slate-500 text-sm">{t("tagline")}</p>
        </div>

        <Card className="shadow-md bg-white">
          <CardHeader>
            <CardTitle>{t("title")}</CardTitle>
            <CardDescription>{t("description")}</CardDescription>
          </CardHeader>
          <CardContent>
            {done ? (
              <div className="flex items-center gap-2 rounded-lg border border-green-200 bg-green-50 px-3 py-4 text-sm text-green-800">
                <CheckCircle2 className="size-5" />
                {t("done")}
              </div>
            ) : (
              <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
                <div className="space-y-1.5">
                  <Label htmlFor="organizationName">{t("organizationName")}</Label>
                  <Input id="organizationName" disabled={isSubmitting} {...register("organizationName")} />
                  {errors.organizationName && (
                    <p className="text-red-600 text-xs">{errors.organizationName.message}</p>
                  )}
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="fullName">{t("ownerName")}</Label>
                  <Input id="fullName" disabled={isSubmitting} {...register("fullName")} />
                  {errors.fullName && (
                    <p className="text-red-600 text-xs">{errors.fullName.message}</p>
                  )}
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="email">{t("ownerEmail")}</Label>
                  <Input id="email" type="email" disabled={isSubmitting} {...register("email")} />
                  {errors.email && (
                    <p className="text-red-600 text-xs">{errors.email.message}</p>
                  )}
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="password">{t("password")}</Label>
                  <div className="relative">
                    <Input
                      id="password"
                      type={showPassword ? "text" : "password"}
                      disabled={isSubmitting}
                      className="pr-10"
                      {...register("password")}
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
                  {errors.password && (
                    <p className="text-red-600 text-xs">{errors.password.message}</p>
                  )}
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="confirmPassword">{t("confirmPassword")}</Label>
                  <Input
                    id="confirmPassword"
                    type="password"
                    disabled={isSubmitting}
                    {...register("confirmPassword")}
                  />
                  {errors.confirmPassword && (
                    <p className="text-red-600 text-xs">{errors.confirmPassword.message}</p>
                  )}
                </div>
                <div className="flex items-center gap-2">
                  <input
                    id="allowSelfRegistration"
                    type="checkbox"
                    disabled={isSubmitting}
                    className="rounded border-slate-300 size-4 accent-indigo-600"
                    {...register("allowSelfRegistration")}
                  />
                  <Label htmlFor="allowSelfRegistration" className="font-normal text-sm cursor-pointer">
                    {t("allowSelfRegistration")}
                  </Label>
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
                      {t("submitting")}
                    </>
                  ) : (
                    t("submit")
                  )}
                </Button>
                <p className="text-xs text-slate-500 text-center">{t("irreversible")}</p>
              </form>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
