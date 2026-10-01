"use client"


import { Suspense, useEffect, useState } from "react"
import { useTranslations } from "next-intl"
import { useSearchParams } from "next/navigation"
import { Link, useRouter } from "@/i18n/navigation"
import { AlertCircle, CheckCircle, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CardDescription,
} from "@/components/ui/card"
import { useAuthStore } from "@/components/providers/StoreProvider"
import { authApiErrorMessage, verifyLogin2FAApi } from "@/lib/auth-api"
import {
  consumeAuthDestination,
  rememberAuthDestination,
  safeInternalPath,
  withNext,
} from "@/lib/auth-redirect"

function VerifyLoginContent() {
  const t = useTranslations("Auth")
  const router = useRouter()
  const searchParams = useSearchParams()
  const token = searchParams.get("token")
  const next = safeInternalPath(searchParams.get("next"))
  const setAuth = useAuthStore((s) => s.setAuth)

  const [status, setStatus] = useState<"loading" | "success" | "error">(
    token ? "loading" : "error",
  )
  const [errorMessage, setErrorMessage] = useState(
    token ? "" : t("verifyLogin.invalidToken"),
  )

  useEffect(() => {
    if (!token) return

    rememberAuthDestination(next)

    const verify = async () => {
      try {
        const res = await verifyLogin2FAApi(token)
        setAuth(res.user, res.accessToken, res.user.tenantId)
        setStatus("success")
        // Redirect to dashboard after a short delay
        setTimeout(() => {
          router.push(consumeAuthDestination())
        }, 1500)
      } catch (e) {
        const msg = authApiErrorMessage(e, t("fallback.loginVerificationFailed"))
        setErrorMessage(msg)
        setStatus("error")
      }
    }

    verify()
  }, [token, next, router, setAuth, t])

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50 px-4 py-8">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="flex items-center justify-center gap-2 mb-2">
            <img src="/logo.png" alt="" className="w-8 h-8" width={32} height={32} />
            <span className="text-2xl font-bold bg-gradient-to-r from-indigo-600 to-violet-600 bg-clip-text text-transparent">
              OpenVie
            </span>
          </div>
          <p className="text-slate-500 text-sm">{t("tagline")}</p>
        </div>

        <Card className="shadow-md bg-white">
          <CardHeader className="text-center">
            {status === "loading" && (
              <>
                <div className="mx-auto w-12 h-12 bg-indigo-100 rounded-full flex items-center justify-center mb-4">
                  <Loader2 className="w-6 h-6 text-indigo-600 animate-spin" />
                </div>
                <CardTitle>{t("verifyLogin.verifying")}</CardTitle>
                <CardDescription>{t("verifyLogin.identity")}</CardDescription>
              </>
            )}

            {status === "success" && (
              <>
                <div className="mx-auto w-12 h-12 bg-green-100 rounded-full flex items-center justify-center mb-4">
                  <CheckCircle className="w-6 h-6 text-green-600" />
                </div>
                <CardTitle>{t("verifyLogin.success")}</CardTitle>
                <CardDescription>{t("verifyLogin.redirecting")}</CardDescription>
              </>
            )}

            {status === "error" && (
              <>
                <div className="mx-auto w-12 h-12 bg-red-100 rounded-full flex items-center justify-center mb-4">
                  <AlertCircle className="w-6 h-6 text-red-600" />
                </div>
                <CardTitle>{t("verificationFailed")}</CardTitle>
                <CardDescription>
                  {errorMessage || t("verifyLogin.expired")}
                </CardDescription>
              </>
            )}
          </CardHeader>

          <CardContent className="space-y-4">
            {status === "error" && (
              <Link href={withNext("/login", next)}>
                <Button variant="outline" className="w-full">
                  {t("backToLogin")}
                </Button>
              </Link>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

export default function VerifyLoginPage() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-slate-50" />}>
      <VerifyLoginContent />
    </Suspense>
  )
}
