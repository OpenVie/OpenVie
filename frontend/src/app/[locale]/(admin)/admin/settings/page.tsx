"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import toast from "react-hot-toast";
import { AlertCircle, KeyRound, Save } from "lucide-react";
import { useApiClient } from "@/hooks/useApiClient";
import { useAuthStore } from "@/components/providers/StoreProvider";
import { getOrganizationApi, setSelfRegistrationApi } from "@/lib/workspace-api";
import type { OrganizationSnapshot } from "@/types";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Link } from "@/i18n/navigation";

export default function SettingsPage() {
  const t = useTranslations("Settings");
  const navigation = useTranslations("Navigation");
  const password = useTranslations("ChangePassword");
  const { request } = useApiClient();
  const user = useAuthStore((s) => s.user);
  const isOwner = user?.orgRole === "ORG_OWNER";

  const [organization, setOrganization] = useState<OrganizationSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const orgSchema = z.object({ allowSelfRegistration: z.boolean() });

  const orgForm = useForm<z.infer<typeof orgSchema>>({ resolver: zodResolver(orgSchema) });

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const org = await getOrganizationApi(request);
      setOrganization(org);
      orgForm.setValue("allowSelfRegistration", org.allowSelfRegistration);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("fallback.load"));
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request, t]);

  useEffect(() => {
    const task = window.setTimeout(() => { void load(); }, 0);
    return () => window.clearTimeout(task);
  }, [load]);

  const saveOrg = async (values: z.infer<typeof orgSchema>) => {
    try {
      await setSelfRegistrationApi(request, values.allowSelfRegistration);
      setOrganization(current => current ? { ...current, allowSelfRegistration: values.allowSelfRegistration } : current);
      toast.success(t("saved"));
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("fallback.save")); }
  };

  if (!isOwner) {
    return <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800">{t("ownerOnly")}</div>;
  }

  return <div className="mx-auto max-w-3xl space-y-6">
    <div><h2 className="text-xl font-semibold text-slate-800">{t("title")}</h2><p className="mt-1 text-sm text-slate-500">{t("description")}</p></div>

    {error && <div role="alert" className="flex items-center justify-between rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-800"><span className="flex items-center gap-2"><AlertCircle className="size-4" />{error}</span><Button size="sm" variant="outline" onClick={() => void load()}>{t("retry")}</Button></div>}

    {loading ? <div className="space-y-3"><Skeleton className="h-40 w-full rounded-xl" /><Skeleton className="h-40 w-full rounded-xl" /></div> : <>
      <Card>
        <CardHeader><CardTitle>{t("organization.title")}</CardTitle><CardDescription>{organization ? t("organization.current", { name: organization.name }) : t("organization.description")}</CardDescription></CardHeader>
        <CardContent>
          <form onSubmit={orgForm.handleSubmit(saveOrg)} className="space-y-4">
            <div className="flex items-center justify-between gap-4 rounded-lg border border-slate-200 p-3">
              <div>
                <Label htmlFor="allowSelfRegistration" className="font-medium">{t("organization.selfRegistration")}</Label>
                <p className="mt-0.5 text-sm text-slate-500">{t("organization.selfRegistrationHint")}</p>
              </div>
              <input id="allowSelfRegistration" type="checkbox" className="rounded border-slate-300 size-5 accent-[#c8f53c]" {...orgForm.register("allowSelfRegistration")} />
            </div>
            <Button type="submit" disabled={orgForm.formState.isSubmitting} size="sm" className="gap-1.5 bg-[#c8f53c] text-[#07090c] hover:bg-[#d9ff68]"><Save className="size-4" />{t("save")}</Button>
          </form>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>{navigation("changePassword")}</CardTitle><CardDescription>{password("description")}</CardDescription></CardHeader>
        <CardContent><Link href="/change-password" className="ov-change-password-link"><KeyRound className="size-4" />{navigation("changePassword")}</Link></CardContent>
      </Card>
    </>}
  </div>;
}
