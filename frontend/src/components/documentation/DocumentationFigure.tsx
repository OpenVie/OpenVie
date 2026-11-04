// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import { useTranslations } from "next-intl";

/**
 * Documentation figure: a labeled illustration (screenshot or SVG diagram)
 * with a numbered-callout caption. Server-rendered — no client JS. Source
 * assets live in `frontend/public/docs/`; numbered callouts inside wireframe
 * SVGs (api-key-*.svg) are explained by the caption text in messages.
 */
export function DocFigure({
  src,
  alt,
  caption,
}: {
  src: string
  alt: string
  caption?: string
}) {
  const t = useTranslations("DocumentationComponents")
  return (
    <figure className="my-6 overflow-hidden rounded-xl border border-[var(--ov-docs-border)] bg-[var(--ov-docs-surface)]">
      {/* eslint-disable-next-line @next/next/no-img-element -- static docs assets, known path */}
      <img
        src={src}
        alt={alt}
        loading="lazy"
        className="mx-auto block w-auto max-w-full"
      />
      {caption ? (
        <figcaption className="border-t border-[var(--ov-docs-border)] px-4 py-3 text-xs leading-relaxed text-[var(--ov-docs-muted)]">
          <span className="font-semibold uppercase tracking-wide">{t("figure")}</span>{" "}
          {caption}
        </figcaption>
      ) : null}
    </figure>
  )
}
