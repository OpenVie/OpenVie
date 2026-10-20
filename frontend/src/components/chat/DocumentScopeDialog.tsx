"use client"

import * as React from "react"
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog"
import { useTranslations } from "next-intl"
import { Check, FileText, MessageSquareText, Search, X } from "lucide-react"

import { liquidGlassPopupStyle } from "@/components/i18n/liquidGlassMenuStyle"
import type { DocumentStatus } from "@/types"
import { useTheme } from "@/hooks/useTheme"
import { cn } from "@/lib/utils"

export const MAX_SCOPE_DOCUMENTS = 20

export type ScopeDocument = {
  id: string
  fileName: string
  status: DocumentStatus | "UPLOADING"
}

export function DocumentScopeDialog({
  open,
  onOpenChange,
  documents,
  selected,
  maxDocuments = MAX_SCOPE_DOCUMENTS,
  onApply,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  documents: ScopeDocument[]
  selected: string[]
  maxDocuments?: number
  onApply: (documentIds: string[]) => void
}) {
  const { theme } = useTheme()
  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop
          className={cn(
            "fixed inset-0 z-50 data-open:animate-in data-open:fade-in-0",
            theme === "light" ? "bg-[rgb(16_24_14_/_0.24)]" : "bg-black/45",
          )}
        />
        <DialogPrimitive.Popup
          className={cn(
            // Mobile: bottom sheet. Desktop: centered panel.
            "fixed inset-x-0 bottom-0 z-50 flex max-h-[78dvh] w-full flex-col rounded-t-3xl",
            "data-open:animate-in data-open:fade-in-0",
            "sm:inset-x-auto sm:bottom-auto sm:left-1/2 sm:top-1/2 sm:max-h-[70vh] sm:max-w-md",
            "sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-3xl sm:data-open:zoom-in-95",
          )}
          style={liquidGlassPopupStyle(theme)}
        >
          <ScopeSelection
            documents={documents}
            selected={selected}
            maxDocuments={maxDocuments}
            onApply={onApply}
          />
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

function ScopeSelection({
  documents,
  selected,
  maxDocuments,
  onApply,
}: {
  documents: ScopeDocument[]
  selected: string[]
  maxDocuments: number
  onApply: (documentIds: string[]) => void
}) {
  const t = useTranslations("Chat")
  const light = useTheme().theme === "light"
  // The selection restarts from `selected` whenever the popup opens: this
  // component only mounts while the popup is shown.
  const [draft, setDraft] = React.useState<Set<string>>(() => new Set(selected))
  const [query, setQuery] = React.useState("")

  const filtered = React.useMemo(() => {
    const needle = query.trim().toLowerCase()
    const matches = needle
      ? documents.filter((document) => document.fileName.toLowerCase().includes(needle))
      : documents
    return [...matches].sort((a, b) =>
      Number(a.status === "COMPLETED") === Number(b.status === "COMPLETED")
        ? 0
        : a.status === "COMPLETED"
          ? -1
          : 1,
    )
  }, [documents, query])

  const toggle = (id: string) => {
    setDraft((current) => {
      const next = new Set(current)
      if (next.has(id)) {
        next.delete(id)
      } else {
        if (next.size >= maxDocuments) return current
        next.add(id)
      }
      return next
    })
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          <span
            className={cn(
              "grid size-9 shrink-0 place-items-center rounded-xl",
              light ? "bg-[rgb(16_24_14_/_0.06)]" : "bg-white/8",
            )}
          >
            <MessageSquareText className="size-4" />
          </span>
          <div className="min-w-0">
            <DialogPrimitive.Title className="block text-sm font-semibold leading-6">
              {t("scopeDialogTitle")}
            </DialogPrimitive.Title>
            <DialogPrimitive.Description
              className={cn(
                "mt-0.5 block text-xs leading-5",
                light ? "text-[rgb(16_24_14_/_0.6)]" : "text-white/60",
              )}
            >
              {t("scopeDialogDescription", { count: maxDocuments })}
            </DialogPrimitive.Description>
          </div>
        </div>
        <DialogPrimitive.Close
          className={cn(
            "grid size-8 shrink-0 place-items-center rounded-lg transition-colors",
            light
              ? "hover:bg-[rgb(16_24_14_/_0.06)] active:bg-[rgb(16_24_14_/_0.1)]"
              : "hover:bg-white/10 active:bg-white/15",
          )}
          aria-label={t("scopeClose")}
        >
          <X className="size-4" />
        </DialogPrimitive.Close>
      </div>

      <label
        className={cn(
          "mt-3 flex items-center gap-2 rounded-xl border px-3",
          light ? "border-[rgb(16_24_14_/_0.14)] bg-white/55" : "border-white/15 bg-white/5",
        )}
      >
        <Search className={cn("size-4 shrink-0", light ? "text-[rgb(16_24_14_/_0.45)]" : "text-white/45")} />
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={t("scopeSearchPlaceholder")}
          className={cn(
            "h-9 w-full min-w-0 bg-transparent text-sm outline-none",
            light ? "placeholder:text-[rgb(16_24_14_/_0.4)]" : "placeholder:text-white/40",
          )}
        />
      </label>

      <div
        className="-mx-1 mt-2 min-h-0 flex-1 overflow-y-auto px-1 py-1"
        role="listbox"
        aria-multiselectable="true"
      >
        {filtered.length === 0 ? (
          <p className={cn("px-2 py-6 text-center text-sm", light ? "text-[rgb(16_24_14_/_0.55)]" : "text-white/55")}>
            {t("scopeEmpty")}
          </p>
        ) : (
          filtered.map((document) => {
            const selectable = document.status === "COMPLETED"
            const checked = draft.has(document.id)
            const blockedByLimit = !checked && draft.size >= maxDocuments
            return (
              <button
                key={document.id}
                type="button"
                role="option"
                aria-selected={checked}
                disabled={!selectable || blockedByLimit}
                onClick={() => toggle(document.id)}
                className={cn(
                  "flex w-full items-center gap-3 rounded-xl px-2.5 py-2.5 text-left transition-colors",
                  "disabled:cursor-not-allowed disabled:opacity-50",
                  light ? "hover:bg-[rgb(16_24_14_/_0.05)]" : "hover:bg-white/10",
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    "grid size-5 shrink-0 place-items-center rounded-md border transition-colors",
                    checked
                      ? light
                        ? "border-transparent bg-[#1f7a4d] text-white"
                        : "border-transparent bg-[#c8f53c] text-[#07090c]"
                      : light
                        ? "border-[rgb(16_24_14_/_0.25)]"
                        : "border-white/25",
                  )}
                >
                  {checked && <Check className="size-3.5" strokeWidth={3} />}
                </span>
                <FileText className={cn("size-4 shrink-0", light ? "text-[rgb(16_24_14_/_0.45)]" : "text-white/45")} />
                <span className="min-w-0 flex-1 truncate text-sm">{document.fileName}</span>
                {!selectable && (
                  <span className={cn("shrink-0 text-[11px]", light ? "text-[rgb(16_24_14_/_0.5)]" : "text-white/50")}>
                    {t(`status.${document.status}`)}
                  </span>
                )}
              </button>
            )
          })
        )}
      </div>

      <div className={cn("mt-3 flex items-center justify-between gap-3 border-t pt-3", light ? "border-[rgb(16_24_14_/_0.1)]" : "border-white/10")}>
        <span className={cn("text-xs", light ? "text-[rgb(16_24_14_/_0.65)]" : "text-white/65")}>
          {draft.size > 0 ? t("scopeSelectedCount", { count: draft.size }) : t("scopeTriggerAll")}
        </span>
        <div className="flex shrink-0 items-center gap-2">
          <button
            type="button"
            onClick={() => setDraft(new Set())}
            disabled={draft.size === 0}
            className={cn(
              "rounded-lg px-2.5 py-1.5 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-40",
              light ? "hover:bg-[rgb(16_24_14_/_0.06)]" : "hover:bg-white/10",
            )}
          >
            {t("scopeClear")}
          </button>
          <button
            type="button"
            onClick={() => onApply([...draft])}
            className={cn(
              "rounded-lg px-3 py-1.5 text-xs font-semibold text-white transition-colors",
              light ? "bg-[#1f7a4d] hover:bg-[#186a42]" : "bg-[#c8f53c] text-[#07090c] hover:bg-[#b7e133]",
            )}
          >
            {t("scopeApply")}
          </button>
        </div>
      </div>
    </div>
  )
}
