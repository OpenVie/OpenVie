"use client";


import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useRouter } from "@/i18n/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslations } from "next-intl";
import { AlertCircle, CheckCircle2, Loader2, Users } from "lucide-react";
import { acceptInvitationApi, authApiErrorMessage, validateInvitationApi } from "@/lib/auth-api";
import { useAuthStore } from "@/components/providers/StoreProvider";
import type { InvitationValidation } from "@/types";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { consumeAuthDestination } from "@/lib/auth-redirect";

type FormValues = { fullName: string; password: string; confirmPassword: string };

function AcceptInvitationContent() {
  const t = useTranslations("Auth")
  const token = useSearchParams().get("token") ?? "";
  const router = useRouter();
  const setAuth = useAuthStore(state => state.setAuth);
  const [invitation, setInvitation] = useState<InvitationValidation | null>(null);
  const [validating, setValidating] = useState(Boolean(token));
  const [pageError, setPageError] = useState<string | null>(token ? null : t("invitation.missingToken"));
  const [accepted, setAccepted] = useState(false);
  const schema = z.object({
    fullName: z.string().trim().min(1, t("validation.enterFullName")).max(255),
    password: z.string().min(8, t("validation.passwordLength")).max(128),
    confirmPassword: z.string(),
  }).refine(values => values.password === values.confirmPassword, {
    path: ["confirmPassword"], message: t("validation.passwordsMismatch"),
  });
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<FormValues>({ resolver: zodResolver(schema) });

  useEffect(() => {
    let active = true;
    if (!token) return;
    validateInvitationApi(token).then(value => { if (active) setInvitation(value); })
      .catch(error => { if (active) setPageError(authApiErrorMessage(error, t("invitation.invalid"))); })
      .finally(() => { if (active) setValidating(false); });
    return () => { active = false; };
  }, [t, token]);

  const submit = async (values: FormValues) => {
    setPageError(null);
    try {
      const auth = await acceptInvitationApi({ token, fullName: values.fullName, password: values.password });
      setAuth(auth.user, auth.accessToken, auth.user.tenantId);
      setAccepted(true);
      window.setTimeout(() => router.replace(consumeAuthDestination()), 500);
    } catch (error) {
      setPageError(authApiErrorMessage(error, t("invitation.acceptFailed")));
    }
  };

  return <div className="flex min-h-screen items-center justify-center bg-slate-50 px-4 py-10"><Card className="w-full max-w-md shadow-md"><CardHeader className="text-center"><div className="mx-auto mb-3 flex size-12 items-center justify-center rounded-full bg-indigo-100">{accepted ? <CheckCircle2 className="size-6 text-green-600" /> : <Users className="size-6 text-indigo-600" />}</div><CardTitle>{accepted ? t("invitation.accepted") : t("invitation.joinTeam")}</CardTitle></CardHeader><CardContent>
    {validating ? <div className="flex items-center justify-center gap-2 py-8 text-sm text-slate-500"><Loader2 className="size-4 animate-spin" />{t("invitation.validating")}</div> : accepted ? <p className="py-6 text-center text-sm text-slate-600">{t("invitation.ready")}</p> : pageError && !invitation ? <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-center text-sm text-red-800"><AlertCircle className="mx-auto mb-2 size-5" />{pageError}</div> : invitation && <>
      <div className="mb-5 rounded-lg bg-slate-50 p-3 text-sm text-slate-600"><p>{t.rich("invitation.invitedAs", { role: () => <strong>{invitation.role === "TENANT_ADMIN" ? t("roles.admin") : t("roles.user")}</strong>, tenant: () => <strong>{invitation.tenantName}</strong> })}</p><p className="mt-1 text-xs text-slate-500">{invitation.email}</p></div>
      <form onSubmit={handleSubmit(submit)} className="space-y-4"><div className="space-y-1.5"><Label htmlFor="fullName">{t("fullName")}</Label><Input id="fullName" autoComplete="name" {...register("fullName")} />{errors.fullName && <p className="text-xs text-red-600">{errors.fullName.message}</p>}</div><div className="space-y-1.5"><Label htmlFor="password">{t("password")}</Label><Input id="password" type="password" autoComplete="new-password" {...register("password")} />{errors.password && <p className="text-xs text-red-600">{errors.password.message}</p>}</div><div className="space-y-1.5"><Label htmlFor="confirmPassword">{t("confirmPassword")}</Label><Input id="confirmPassword" type="password" autoComplete="new-password" {...register("confirmPassword")} />{errors.confirmPassword && <p className="text-xs text-red-600">{errors.confirmPassword.message}</p>}</div>{pageError && <div role="alert" className="flex gap-2 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-800"><AlertCircle className="mt-0.5 size-4 shrink-0" />{pageError}</div>}<Button type="submit" disabled={isSubmitting} className="w-full bg-indigo-600 text-white hover:bg-indigo-700">{isSubmitting ? <><Loader2 className="size-4 animate-spin" />{t("invitation.creating")}</> : t("invitation.accept")}</Button></form>
    </>}
  </CardContent></Card></div>;
}

export default function AcceptInvitationPage() {
  return <Suspense fallback={<div className="min-h-screen bg-slate-50" />}><AcceptInvitationContent /></Suspense>;
}
