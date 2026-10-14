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
    password: z.string().min(12, t("validation.passwordLength")).max(128),
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
      setAuth(auth.user, auth.accessToken);
      setAccepted(true);
      window.setTimeout(() => router.replace(consumeAuthDestination()), 500);
    } catch (error) {
      setPageError(authApiErrorMessage(error, t("invitation.acceptFailed")));
    }
  };

  return <>
    <div className="ov-auth-card" style={{ maxWidth: 440 }}>
      <div className="flex flex-col items-center mb-4">
        <div className="mb-3 flex size-12 items-center justify-center rounded-full" style={{ background: accepted ? 'rgba(200,245,60,.15)' : 'rgba(200,245,60,.15)' }}>{accepted ? <CheckCircle2 className="size-6 text-[#c8f53c]" /> : <Users className="size-6 text-[#c8f53c]" />}</div>
        <h1>{accepted ? t("invitation.accepted") : t("invitation.joinTeam")}</h1>
      </div>

    {validating ? <div className="flex items-center justify-center gap-2 py-8 text-sm text-[#a9b3ab]"><Loader2 className="size-4 animate-spin" />{t("invitation.validating")}</div> : accepted ? <p className="py-6 text-center text-sm text-[#a9b3ab]">{t("invitation.ready")}</p> : pageError && !invitation ? <div className="ov-auth-error justify-center flex-col items-center text-center"><AlertCircle className="mx-auto mb-2 size-5" />{pageError}</div> : invitation && <>
      <div className="mb-5 rounded-lg p-3 text-sm text-[#a9b3ab]" style={{ background: 'rgba(255,255,255,.05)' }}><p>{t.rich("invitation.invitedAs", { role: () => <strong className="text-[#f2f5f0]">{invitation.role === "WORKSPACE_ADMIN" ? t("roles.admin") : t("roles.user")}</strong>, workspace: () => <strong className="text-[#f2f5f0]">{invitation.workspaceName}</strong>, organization: () => <strong className="text-[#f2f5f0]">{invitation.organizationName}</strong> })}</p><p className="mt-1 text-xs text-[#8e9992]">{invitation.email}</p></div>
      <form onSubmit={handleSubmit(submit)} className="space-y-4"><div className="space-y-1.5"><label htmlFor="fullName" className="ov-auth-label">{t("fullName")}</label><input id="fullName" autoComplete="name" className="ov-auth-input" {...register("fullName")} />{errors.fullName && <p className="text-xs text-red-400">{errors.fullName.message}</p>}</div><div className="space-y-1.5"><label htmlFor="password" className="ov-auth-label">{t("password")}</label><input id="password" type="password" autoComplete="new-password" className="ov-auth-input" {...register("password")} />{errors.password && <p className="text-xs text-red-400">{errors.password.message}</p>}</div><div className="space-y-1.5"><label htmlFor="confirmPassword" className="ov-auth-label">{t("confirmPassword")}</label><input id="confirmPassword" type="password" autoComplete="new-password" className="ov-auth-input" {...register("confirmPassword")} />{errors.confirmPassword && <p className="text-xs text-red-400">{errors.confirmPassword.message}</p>}</div>{pageError && <div role="alert" className="ov-auth-error"><AlertCircle className="mt-0.5 size-4 shrink-0" />{pageError}</div>}<button type="submit" disabled={isSubmitting} className="ov-auth-btn">{isSubmitting ? <><Loader2 className="size-4 animate-spin" />{t("invitation.creating")}</> : t("invitation.accept")}</button></form>
    </>}
  </div>
  </>;
}

export default function AcceptInvitationPage() {
  return <Suspense fallback={<div className="min-h-screen" />}><AcceptInvitationContent /></Suspense>;
}
