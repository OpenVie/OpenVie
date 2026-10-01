"use client";

import { useEffect, useState } from "react";
import Image from "next/image";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslations } from "next-intl";
import { Link, useRouter } from "@/i18n/navigation";
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
import {
  authApiErrorMessage,
  registrationStatusApi,
  registerApi,
} from "@/lib/auth-api";
import { cn } from "@/lib/utils";

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
            <CardTitle>{tr("title")}</CardTitle>
            <CardDescription>{tr("description")}</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
              <div className="space-y-1.5">
                <Label htmlFor="fullName">{t("fullName")}</Label>
                <Input id="fullName" autoComplete="name" disabled={isSubmitting} {...register("fullName")} />
                {errors.fullName && <p className="text-red-600 text-xs">{errors.fullName.message}</p>}
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="email">{t("email")}</Label>
                <Input id="email" type="email" autoComplete="email" disabled={isSubmitting} {...register("email")} />
                {errors.email && <p className="text-red-600 text-xs">{errors.email.message}</p>}
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="password">{t("password")}</Label>
                <div className="relative">
                  <Input
                    id="password"
                    type={showPassword ? "text" : "password"}
                    autoComplete="new-password"
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
                {errors.password && <p className="text-red-600 text-xs">{errors.password.message}</p>}
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="confirmPassword">{t("confirmPassword")}</Label>
                <Input
                  id="confirmPassword"
                  type="password"
                  autoComplete="new-password"
                  disabled={isSubmitting}
                  {...register("confirmPassword")}
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
                    {tr("submitting")}
                  </>
                ) : (
                  tr("submit")
                )}
              </Button>
              <p className="text-center text-sm text-slate-500">
                {tr("haveAccount")}{" "}
                <Link href="/login" className="text-indigo-600 hover:text-indigo-700 underline underline-offset-4">
                  {t("login.signIn")}
                </Link>
              </p>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
