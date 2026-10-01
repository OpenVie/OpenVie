"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import toast from "react-hot-toast";
import { AlertCircle, Mail, Plus, Save, Send, Trash2 } from "lucide-react";
import { useApiClient } from "@/hooks/useApiClient";
import { useAuthStore } from "@/components/providers/StoreProvider";
import {
  deleteChannelApi,
  listChannelsApi,
  setChannelEnabledApi,
  testChannelApi,
  upsertChannelApi,
  type ChannelPayload,
} from "@/lib/channels-api";
import { getOrganizationApi, setSelfRegistrationApi } from "@/lib/workspace-api";
import type { NotificationChannelView, OrganizationSnapshot } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";

const CHANNEL_TYPES = ["smtp", "sendgrid", "brevo"] as const;
type ChannelType = (typeof CHANNEL_TYPES)[number];

type ChannelForm = {
  type: ChannelType;
  fromEmail: string;
  fromName: string;
  host: string;
  port: string;
  username: string;
  password: string;
  apiKey: string;
  auth: boolean;
  starttls: boolean;
  enabled: boolean;
};

export default function SettingsPage() {
  const t = useTranslations("Settings");
  const { request } = useApiClient();
  const user = useAuthStore((s) => s.user);
  const isOwner = user?.orgRole === "ORG_OWNER";

  const [organization, setOrganization] = useState<OrganizationSnapshot | null>(null);
  const [channels, setChannels] = useState<NotificationChannelView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<NotificationChannelView | null>(null);
  const [showEditor, setShowEditor] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [testOpen, setTestOpen] = useState(false);

  const orgSchema = z.object({ allowSelfRegistration: z.boolean() });

  const channelSchema = z
    .object({
      type: z.enum(CHANNEL_TYPES),
      fromEmail: z.string().email(t("channels.validEmail")),
      fromName: z.string().min(1, t("channels.nameRequired")),
      host: z.string(),
      port: z.string(),
      username: z.string(),
      password: z.string(),
      apiKey: z.string(),
      auth: z.boolean(),
      starttls: z.boolean(),
      enabled: z.boolean(),
    })
    .refine((values) => values.type === "smtp" || values.apiKey.length > 0, {
      path: ["apiKey"],
      message: t("channels.keyRequired"),
    })
    .refine((values) => values.type !== "smtp" || values.host.trim().length > 0, {
      path: ["host"],
      message: t("channels.hostRequired"),
    });

  const orgForm = useForm<z.infer<typeof orgSchema>>({ resolver: zodResolver(orgSchema) });
  const channelForm = useForm<ChannelForm>({
    resolver: zodResolver(channelSchema),
    defaultValues: {
      type: "smtp", fromEmail: "", fromName: "OpenVie", host: "", port: "587",
      username: "", password: "", apiKey: "", auth: false, starttls: false, enabled: true,
    },
  });
  const testSchema = z.object({ email: z.string().email(t("channels.validEmail")) });
  const testForm = useForm<z.infer<typeof testSchema>>({ resolver: zodResolver(testSchema) });

  const [channelType, setChannelType] = useState<ChannelType>("smtp");

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [org, list] = await Promise.all([
        getOrganizationApi(request),
        listChannelsApi(request),
      ]);
      setOrganization(org);
      setChannels(list);
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

  const openEditor = (channel: NotificationChannelView | null) => {
    setEditing(channel);
    channelForm.reset({
      type: (channel?.type as ChannelType | undefined) ?? "smtp",
      fromEmail: "", fromName: "OpenVie", host: "", port: "587", username: "", password: "",
      apiKey: "", auth: false, starttls: false, enabled: channel?.enabled ?? true,
    });
    setChannelType((channel?.type as ChannelType | undefined) ?? "smtp");
    setShowEditor(true);
  };

  const saveChannel = async (values: ChannelForm) => {
    const payload: ChannelPayload = {
      enabled: values.enabled,
      fromEmail: values.fromEmail,
      fromName: values.fromName,
    };
    if (values.type === "smtp") {
      payload.host = values.host;
      payload.port = Number(values.port) || 587;
      payload.username = values.username;
      payload.auth = values.auth;
      payload.starttls = values.starttls;
      if (values.password) payload.password = values.password;
    } else if (values.apiKey) {
      payload.apiKey = values.apiKey;
    }
    try {
      await upsertChannelApi(request, values.type, payload);
      toast.success(t("channels.saved"));
      setShowEditor(false);
      await load();
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("channels.fallback.save")); }
  };

  const toggleEnabled = async (channel: NotificationChannelView) => {
    setBusyId(channel.id);
    try {
      await setChannelEnabledApi(request, channel.id, !channel.enabled);
      await load();
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("channels.fallback.toggle")); }
    finally { setBusyId(null); }
  };

  const remove = async (channel: NotificationChannelView) => {
    if (!window.confirm(t("channels.confirmDelete", { type: channel.type }))) return;
    setBusyId(channel.id);
    try {
      await deleteChannelApi(request, channel.id);
      toast.success(t("channels.deleted"));
      await load();
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("channels.fallback.delete")); }
    finally { setBusyId(null); }
  };

  const sendTest = async (values: z.infer<typeof testSchema>) => {
    try {
      await testChannelApi(request, values.email);
      toast.success(t("channels.testSent"));
      setTestOpen(false);
    } catch (cause) { toast.error(cause instanceof Error ? cause.message : t("channels.testFailed")); }
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
              <input id="allowSelfRegistration" type="checkbox" className="rounded border-slate-300 size-5 accent-indigo-600" {...orgForm.register("allowSelfRegistration")} />
            </div>
            <Button type="submit" disabled={orgForm.formState.isSubmitting} size="sm" className="gap-1.5 bg-indigo-600 text-white hover:bg-indigo-700"><Save className="size-4" />{t("save")}</Button>
          </form>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex-row items-center justify-between">
          <div><CardTitle>{t("channels.title")}</CardTitle><CardDescription>{t("channels.description")}</CardDescription></div>
          <div className="flex gap-2">
            <Button size="sm" variant="outline" onClick={() => setTestOpen(true)} disabled={channels.length === 0}><Send className="mr-1 size-4" />{t("channels.test")}</Button>
            <Button size="sm" onClick={() => openEditor(null)} className="gap-1.5 bg-indigo-600 text-white hover:bg-indigo-700"><Plus className="size-4" />{t("channels.add")}</Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          {channels.length === 0 ? <p className="py-6 text-center text-sm text-slate-500">{t("channels.empty")}</p> : channels.map(channel => (
            <div key={channel.id} className="flex items-center justify-between gap-4 rounded-lg border border-slate-200 p-3">
              <div className="min-w-0">
                <p className="flex items-center gap-2 font-medium text-slate-800"><Mail className="size-4 text-slate-400" />{channel.type}
                  {channel.enabled ? <Badge>{t("channels.enabled")}</Badge> : <Badge variant="outline">{t("channels.disabled")}</Badge>}
                  {channel.credentialsStored && <Badge variant="outline">{t("channels.credentialsStored")}</Badge>}
                </p>
                {channel.lastDeliveryError && <p className="mt-1 truncate text-xs text-red-600">{channel.lastDeliveryError}</p>}
              </div>
              <div className="flex shrink-0 gap-2">
                <Button size="sm" variant="outline" disabled={busyId === channel.id} onClick={() => void toggleEnabled(channel)}>{channel.enabled ? t("channels.disable") : t("channels.enable")}</Button>
                <Button size="sm" variant="outline" disabled={busyId === channel.id} onClick={() => openEditor(channel)}>{t("channels.edit")}</Button>
                <Button size="sm" variant="outline" disabled={busyId === channel.id} onClick={() => void remove(channel)}><Trash2 className="size-4 text-red-600" /></Button>
              </div>
            </div>
          ))}
        </CardContent>
      </Card>
    </>}

    <Dialog open={showEditor} onOpenChange={setShowEditor}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader><DialogTitle>{editing ? t("channels.editTitle", { type: editing.type }) : t("channels.addTitle")}</DialogTitle></DialogHeader>
        <form onSubmit={channelForm.handleSubmit(saveChannel)} className="space-y-4">
          <div className="space-y-1.5"><Label>{t("channels.type")}</Label>
            <Select value={channelType} onValueChange={value => { setChannelType(value as ChannelType); channelForm.setValue("type", value as ChannelType); }} disabled={editing !== null}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>{CHANNEL_TYPES.map(item => <SelectItem key={item} value={item}>{item}</SelectItem>)}</SelectContent>
            </Select>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-1.5"><Label htmlFor="fromEmail">{t("channels.fromEmail")}</Label><Input id="fromEmail" type="email" {...channelForm.register("fromEmail")} />{channelForm.formState.errors.fromEmail && <p className="text-xs text-red-600">{channelForm.formState.errors.fromEmail.message}</p>}</div>
            <div className="space-y-1.5"><Label htmlFor="fromName">{t("channels.fromName")}</Label><Input id="fromName" {...channelForm.register("fromName")} />{channelForm.formState.errors.fromName && <p className="text-xs text-red-600">{channelForm.formState.errors.fromName.message}</p>}</div>
          </div>
          {channelType === "smtp" ? <>
            <div className="grid gap-4 sm:grid-cols-3">
              <div className="space-y-1.5 sm:col-span-2"><Label htmlFor="host">{t("channels.host")}</Label><Input id="host" placeholder="smtp.example.com" {...channelForm.register("host")} />{channelForm.formState.errors.host && <p className="text-xs text-red-600">{channelForm.formState.errors.host.message}</p>}</div>
              <div className="space-y-1.5"><Label htmlFor="port">{t("channels.port")}</Label><Input id="port" inputMode="numeric" {...channelForm.register("port")} /></div>
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1.5"><Label htmlFor="username">{t("channels.username")}</Label><Input id="username" autoComplete="off" {...channelForm.register("username")} /></div>
              <div className="space-y-1.5"><Label htmlFor="password">{editing ? t("channels.passwordKeep") : t("channels.password")}</Label><Input id="password" type="password" autoComplete="new-password" {...channelForm.register("password")} /></div>
            </div>
            <div className="flex gap-6">
              <label className="flex items-center gap-2 text-sm"><input type="checkbox" className="rounded accent-indigo-600" {...channelForm.register("auth")} />{t("channels.smtpAuth")}</label>
              <label className="flex items-center gap-2 text-sm"><input type="checkbox" className="rounded accent-indigo-600" {...channelForm.register("starttls")} />STARTTLS</label>
            </div>
          </> : <>
            <div className="space-y-1.5"><Label htmlFor="apiKey">{t("channels.apiKey")}</Label><Input id="apiKey" type="password" autoComplete="new-password" {...channelForm.register("apiKey")} />{channelForm.formState.errors.apiKey && <p className="text-xs text-red-600">{channelForm.formState.errors.apiKey.message}</p>}<p className="text-xs text-slate-500">{editing ? t("channels.apiKeyKeepHint") : t("channels.apiKeyHint")}</p></div>
          </>}
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" className="rounded accent-indigo-600" {...channelForm.register("enabled")} />{t("channels.enableAfterSave")}</label>
          <DialogFooter><Button type="submit" disabled={channelForm.formState.isSubmitting} className="bg-indigo-600 text-white hover:bg-indigo-700">{t("save")}</Button></DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <Dialog open={testOpen} onOpenChange={setTestOpen}>
      <DialogContent><DialogHeader><DialogTitle>{t("channels.testTitle")}</DialogTitle></DialogHeader>
        <form onSubmit={testForm.handleSubmit(sendTest)} className="space-y-4">
          <div className="space-y-1.5"><Label htmlFor="testEmail">{t("channels.testEmail")}</Label><Input id="testEmail" type="email" {...testForm.register("email")} />{testForm.formState.errors.email && <p className="text-xs text-red-600">{testForm.formState.errors.email.message}</p>}</div>
          <DialogFooter><Button type="submit" disabled={testForm.formState.isSubmitting} className="bg-indigo-600 text-white hover:bg-indigo-700">{t("channels.test")}</Button></DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  </div>;
}
