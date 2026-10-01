"use client";


import { Suspense, useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslations } from "next-intl";
import { useSearchParams } from "next/navigation";
import { Link, useRouter } from "@/i18n/navigation";
import { AlertCircle, Eye, EyeOff, Loader2, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CardDescription,
} from "@/components/ui/card";
import { useAuthStore } from "@/components/providers/StoreProvider";
import { authApiErrorMessage, loginApi } from "@/lib/auth-api";
import { cn } from "@/lib/utils";
import {
  consumeAuthDestination,
  rememberAuthDestination,
  safeInternalPath,
  withNext,
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
  const loginSchema = z.object({
    email: z.string().min(1, t("validation.emailRequired")).email(t("validation.invalidEmail")),
    password: z.string().min(1, t("validation.passwordRequired")),
    rememberMe: z.boolean(),
  });

  useEffect(() => {
    rememberAuthDestination(next);
  }, [next]);

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

      // Check if 2FA is required
      if ("requires2FA" in res && res.requires2FA) {
        // Redirect to check-login-email page for 2FA
        router.push(
          withNext(
            `/check-login-email?email=${encodeURIComponent(res.email)}`,
            next,
          ),
        );
        return;
      }

      // Direct login (AuthResponse)
      if ("accessToken" in res && "user" in res) {
        setAuth(res.user, res.accessToken, res.user.tenantId);
        router.push(consumeAuthDestination());
      }
    } catch (e) {
      const msg = authApiErrorMessage(e, t("fallback.invalidCredentials"));
      if (msg.includes("suspended")) {
        setApiError(msg);
      } else {
        setApiError(msg);
      }
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50 px-4">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="flex items-center justify-center gap-2 mb-2">
            <img
              src="/logo.png"
              alt=""
              className="w-8 h-8"
              width={32}
              height={32}
            />
            <span className="text-2xl font-bold bg-gradient-to-r from-indigo-600 to-violet-600 bg-clip-text text-transparent">
              OpenVie
            </span>
          </div>
          <p className="text-slate-500 text-sm">{t("tagline")}</p>
        </div>

        <Card className="shadow-md bg-white">
          <CardHeader>
            <CardTitle>{t("login.title")}</CardTitle>
            <CardDescription>{t("login.description")}</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
              <div className="space-y-1.5">
                <Label htmlFor="email">{t("email")}</Label>
                <Input
                  id="email"
                  type="email"
                  placeholder="you@company.com"
                  disabled={isSubmitting}
                  aria-invalid={!!errors.email}
                  {...register("email", { onChange: () => setApiError(null) })}
                />
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
                    placeholder="••••••••"
                    disabled={isSubmitting}
                    className="pr-10"
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
                    className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-500 hover:text-slate-800 p-1 rounded-md disabled:opacity-50"
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
                  <p className="text-red-600 text-xs">
                    {errors.password.message}
                  </p>
                )}
              </div>

              <div className="flex items-center gap-2">
                <input
                  id="rememberMe"
                  type="checkbox"
                  disabled={isSubmitting}
                  className="rounded border-slate-300 size-4 accent-indigo-600"
                  {...register("rememberMe", {
                    onChange: () => setApiError(null),
                  })}
                />
                <Label
                  htmlFor="rememberMe"
                  className="font-normal text-sm cursor-pointer"
                >
                  {t("login.rememberMe")}
                </Label>
              </div>

              {apiError && (
                <div
                  role="alert"
                  className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800"
                >
                  <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                  <p className="flex-1">{apiError}</p>
                  <button
                    type="button"
                    onClick={() => setApiError(null)}
                    className="text-red-700 hover:text-red-900 p-0.5 rounded"
                    aria-label={t("dismissError")}
                  >
                    <X className="w-4 h-4" />
                  </button>
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
                    {t("login.signingIn")}
                  </>
                ) : (
                  t("login.signIn")
                )}
              </Button>
            </form>

            <div className="text-center text-sm text-slate-500 mt-4 space-y-2">
              <p>{t("login.provisioned")}</p>
              <p>
                <Link
                  href="/documentation"
                  className="text-indigo-600 hover:text-indigo-700 underline underline-offset-4"
                >
                  {tNav("documentation")}
                </Link>
              </p>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

export default function LoginPage() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-slate-50" />}>
      <LoginContent />
    </Suspense>
  );
}
