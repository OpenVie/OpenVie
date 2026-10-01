"use client"


import { Suspense, useState, useEffect, useCallback } from "react"
import { useTranslations } from "next-intl"
import { useSearchParams } from "next/navigation"
import { Link } from "@/i18n/navigation"
import { Mail, ArrowLeft, Loader2, AlertCircle, CheckCircle } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CardDescription,
} from "@/components/ui/card"
import { authApiErrorMessage, resendLogin2FAApi } from "@/lib/auth-api"
import {
  rememberAuthDestination,
  safeInternalPath,
  withNext,
} from "@/lib/auth-redirect"

const COOLDOWN_SECONDS = 60

function CheckLoginEmailContent() {
  const t = useTranslations("Auth")
  const searchParams = useSearchParams()
  const email = searchParams.get("email") || t("yourEmail")
  const next = safeInternalPath(searchParams.get("next"))

  const [countdown, setCountdown] = useState(COOLDOWN_SECONDS)
  const [isResending, setIsResending] = useState(false)
  const [message, setMessage] = useState<{ type: "success" | "error"; text: string } | null>(null)
  const [isSuspended, setIsSuspended] = useState(false)

  useEffect(() => {
    rememberAuthDestination(next)
  }, [next])

  useEffect(() => {
    if (countdown > 0) {
      const timer = setTimeout(() => setCountdown(countdown - 1), 1000)
      return () => clearTimeout(timer)
    }
  }, [countdown])

  const handleResend = useCallback(async () => {
    if (countdown > 0 || isResending || isSuspended) return

    setIsResending(true)
    setMessage(null)

    try {
      const res = await resendLogin2FAApi(email)
      setCountdown(COOLDOWN_SECONDS)
      setMessage({ type: "success", text: res.message })
    } catch (e) {
      const errorMsg = authApiErrorMessage(e, t("fallback.resendFailed"))

      if (errorMsg.includes("suspended")) {
        setIsSuspended(true)
        setMessage({ type: "error", text: errorMsg })
      } else {
        setMessage({ type: "error", text: errorMsg })
      }
    } finally {
      setIsResending(false)
    }
  }, [countdown, email, isResending, isSuspended, t])

  if (isSuspended) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-50 px-4 py-8">
        <div className="w-full max-w-md">
          <Card className="shadow-md bg-white">
            <CardHeader className="text-center">
              <div className="mx-auto w-12 h-12 bg-red-100 rounded-full flex items-center justify-center mb-4">
                <AlertCircle className="w-6 h-6 text-red-600" />
              </div>
              <CardTitle>{t("suspended.title")}</CardTitle>
              <CardDescription>{t("suspended.description")}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <p className="text-sm text-slate-600 text-center">
                {message?.text || t("suspended.contactSupport")}
              </p>
              <Link href={withNext("/login", next)}>
                <Button variant="outline" className="w-full">
                  <ArrowLeft className="w-4 h-4 mr-2" />
                  {t("backToLogin")}
                </Button>
              </Link>
            </CardContent>
          </Card>
        </div>
      </div>
    )
  }

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
            <div className="mx-auto w-12 h-12 bg-indigo-100 rounded-full flex items-center justify-center mb-4">
              <Mail className="w-6 h-6 text-indigo-600" />
            </div>
            <CardTitle>{t("checkLogin.title")}</CardTitle>
            <CardDescription>{t("checkLogin.sentTo")}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-6">
            <div className="text-center">
              <p className="font-medium text-slate-900">{email}</p>
            </div>

            {message && (
              <div
                className={`flex items-start gap-2 p-3 rounded-lg text-sm ${
                  message.type === "success"
                    ? "bg-green-50 text-green-700"
                    : "bg-red-50 text-red-700"
                }`}
              >
                {message.type === "success" ? (
                  <CheckCircle className="w-4 h-4 mt-0.5 shrink-0" />
                ) : (
                  <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
                )}
                {message.text}
              </div>
            )}

            <div className="bg-slate-50 rounded-lg p-4 text-sm text-slate-600">
              <p className="mb-2">{t("checkLogin.instructions")}</p>
              <p>{t("checkEmail.spam")}</p>
            </div>

            <Button
              onClick={handleResend}
              disabled={countdown > 0 || isResending}
              className="w-full"
            >
              {isResending ? (
                <>
                  <Loader2 className="w-4 h-4 mr-2 animate-spin" />
                  {t("sending")}
                </>
              ) : countdown > 0 ? (
                <>{t("resendIn", { seconds: countdown })}</>
              ) : (
                t("resendVerification")
              )}
            </Button>

            <Link href={withNext("/login", next)}>
              <Button variant="outline" className="w-full">
                <ArrowLeft className="w-4 h-4 mr-2" />
                {t("backToLogin")}
              </Button>
            </Link>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

export default function CheckLoginEmailPage() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-slate-50" />}>
      <CheckLoginEmailContent />
    </Suspense>
  )
}
