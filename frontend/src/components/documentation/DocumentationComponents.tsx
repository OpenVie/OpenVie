"use client"

import { type ReactNode } from "react"
import { useLocale, useTranslations } from "next-intl"
import { Link, usePathname } from "@/i18n/navigation"
import type { AppLocale } from "@/i18n/routing"
import { documentationPage } from "@/lib/documentation"
import { ExternalLink, Info, Lightbulb, ShieldAlert, TriangleAlert } from "lucide-react"
import { cn } from "@/lib/utils"

export function DocArticle({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  const t = useTranslations("DocumentationComponents")
  const pathname = usePathname()
  const locale = useLocale() as AppLocale
  const localized = documentationPage(pathname, locale)
  return (
    <article className="min-w-0 pb-10">
      <header className="mb-10 border-b border-[var(--ov-docs-border)] pb-8">
        <p className="mb-3 text-sm font-semibold text-primary">{t("eyebrow")}</p>
        <h1 className="text-3xl font-bold tracking-tight text-[var(--ov-docs-text)] sm:text-4xl">{localized?.title ?? title}</h1>
        <p className="mt-4 max-w-3xl text-lg leading-8 text-[var(--ov-docs-muted)]">{localized?.description ?? description}</p>
      </header>
      <div className="space-y-10 text-[15px] leading-7 text-[var(--ov-docs-text)]">{children}</div>
    </article>
  )
}

export function DocSection({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  const pathname = usePathname()
  const locale = useLocale() as AppLocale
  const localizedTitle = documentationPage(pathname, locale).sections.find((section) => section.id === id)?.title
  return (
    <section id={id} className="scroll-mt-24 space-y-4">
      <h2 className="text-2xl font-semibold tracking-tight text-[var(--ov-docs-text)]">{localizedTitle ?? title}</h2>
      {children}
    </section>
  )
}

export function Steps({ children }: { children: ReactNode }) {
  return <ol className="relative space-y-6 border-l border-[var(--ov-docs-border)] pl-8 [counter-reset:step]">{children}</ol>
}

export function Step({ title, children }: { title: string; children: ReactNode }) {
  return (
    <li className="relative [counter-increment:step]">
      <span className="absolute -left-[2.72rem] grid size-6 place-items-center rounded-full bg-primary text-xs font-semibold text-primary-foreground before:content-[counter(step)]" />
      <h3 className="font-semibold text-[var(--ov-docs-text)]">{title}</h3>
      <div className="mt-1 text-[var(--ov-docs-muted)]">{children}</div>
    </li>
  )
}

const calloutStyles = {
  note: { box: "border-primary/40 bg-primary/10 text-[var(--ov-docs-text)]", icon: Info, labelKey: "note" as const },
  tip: { box: "border-primary/40 bg-primary/10 text-[var(--ov-docs-text)]", icon: Lightbulb, labelKey: "tip" as const },
  warning: { box: "border-primary/40 bg-primary/10 text-[var(--ov-docs-text)]", icon: TriangleAlert, labelKey: "warning" as const },
  security: { box: "border-primary/40 bg-primary/10 text-[var(--ov-docs-text)]", icon: ShieldAlert, labelKey: "security" as const },
}

export function Callout({ type = "note", title, children }: { type?: keyof typeof calloutStyles; title?: string; children: ReactNode }) {
  const t = useTranslations("DocumentationComponents")
  const style = calloutStyles[type]
  const Icon = style.icon
  return (
    <aside className={cn("rounded-lg border p-4", style.box)}>
      <div className="flex gap-3">
        <Icon className="mt-0.5 size-5 shrink-0" />
        <div className="min-w-0">
          <p className="font-semibold">{title ?? t(style.labelKey)}</p>
          <div className="mt-1 text-sm leading-6 opacity-90">{children}</div>
        </div>
      </div>
    </aside>
  )
}

export type Parameter = { name: string; type: string; required?: boolean; description: ReactNode }

export function ParameterTable({ parameters }: { parameters: Parameter[] }) {
  const t = useTranslations("DocumentationComponents")
  return (
    <div className="overflow-x-auto rounded-lg border border-[var(--ov-docs-border)]">
      <table className="w-full min-w-[620px] border-collapse text-left text-sm">
        <thead className="bg-[var(--ov-docs-surface)] text-xs uppercase tracking-wide text-[var(--ov-docs-muted)]">
          <tr><th className="px-4 py-3">{t("field")}</th><th className="px-4 py-3">{t("type")}</th><th className="px-4 py-3">{t("required")}</th><th className="px-4 py-3">{t("description")}</th></tr>
        </thead>
        <tbody className="divide-y divide-[var(--ov-docs-border)] bg-[var(--ov-docs-surface)]">
          {parameters.map((parameter) => (
            <tr key={parameter.name} className="align-top">
              <td className="px-4 py-3 font-mono text-xs font-semibold text-primary">{parameter.name}</td>
              <td className="px-4 py-3 font-mono text-xs text-[var(--ov-docs-muted)]">{parameter.type}</td>
              <td className="px-4 py-3 text-[var(--ov-docs-muted)]">{parameter.required ? t("yes") : t("no")}</td>
              <td className="px-4 py-3 text-[var(--ov-docs-muted)]">{parameter.description}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function FeatureBadge({ children, kind = "feature" }: { children: ReactNode; kind?: "feature" | "pro" | "admin" }) {
  const style = kind === "feature" ? "border-primary/40 bg-primary/10 text-primary" : "border-[var(--ov-docs-border)] bg-[var(--ov-docs-surface)] text-[var(--ov-docs-text)]"
  return <span className={cn("inline-flex rounded-full border px-2.5 py-0.5 text-xs font-semibold", style)}>{children}</span>
}

export function RelatedLinks({ links }: { links: Array<{ href: string; title: string; description: string }> }) {
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      {links.map((link) => (
        <Link key={link.href} href={link.href} className="group rounded-lg border border-[var(--ov-docs-border)] p-4 transition hover:border-primary/30 hover:bg-primary/5">
          <span className="flex items-center gap-2 font-semibold text-[var(--ov-docs-text)] group-hover:text-primary">{link.title}<ExternalLink className="size-3.5" /></span>
          <span className="mt-1 block text-sm leading-6 text-[var(--ov-docs-muted)]">{link.description}</span>
        </Link>
      ))}
    </div>
  )
}

export function BulletList({ children }: { children: ReactNode }) {
  return <ul className="list-disc space-y-2 pl-6 marker:text-primary">{children}</ul>
}
