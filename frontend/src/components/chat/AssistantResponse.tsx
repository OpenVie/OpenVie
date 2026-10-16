"use client";

import type { ReactNode } from "react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/navigation";
import { ExternalLink, FileText } from "lucide-react";

import { documentViewerHref } from "@/lib/document-links";
import type { ChatCitation } from "@/types";

function splitTableRow(line: string): string[] {
  return line
    .trim()
    .replace(/^\||\|$/g, "")
    .split("|")
    .map((cell) => cell.trim());
}

function InlineMarkdown({
  text,
  citations,
}: {
  text: string;
  citations: Map<string, ChatCitation>;
}) {
  const t = useTranslations("AssistantResponse");
  const tokens = text.split(/(\[S\d+\]|\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+\]\([^)]+\))/g);
  return tokens.map((token, index) => {
    const citation = citations.get(token.slice(1, -1));
    if (citation) {
      return (
        <Link
          key={`${token}-${index}`}
          href={documentViewerHref(citation.document_id, [citation])}
          className="mx-0.5 inline-flex translate-y-[-1px] items-center rounded-md bg-primary/10 px-1.5 py-0.5 text-xs font-semibold text-primary no-underline transition-colors hover:bg-primary/20"
          title={t("openSourceIn", { source: citation.source_name })}
        >
          {citation.id}
        </Link>
      );
    }
    if (token.startsWith("**") && token.endsWith("**")) {
      return <strong key={index}>{token.slice(2, -2)}</strong>;
    }
    if (token.startsWith("`") && token.endsWith("`")) {
      return (
        <code key={index} className="rounded bg-muted px-1.5 py-0.5 font-mono text-[0.9em] text-foreground">
          {token.slice(1, -1)}
        </code>
      );
    }
    const link = token.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
    if (link) {
      const href = link[2] ?? "";
      if (!/^(https?:\/\/|mailto:|\/)/i.test(href)) return link[1];
      return (
        <a
          key={index}
          href={href}
          target="_blank"
          rel="noreferrer"
          className="font-medium text-primary underline underline-offset-2"
        >
          {link[1]}
        </a>
      );
    }
    return token;
  });
}

function markdownBlocks(content: string, citations: Map<string, ChatCitation>): ReactNode[] {
  const lines = content.replace(/\r\n/g, "\n").split("\n");
  const blocks: ReactNode[] = [];
  let index = 0;

  while (index < lines.length) {
    const line = lines[index] ?? "";
    if (!line.trim()) {
      index += 1;
      continue;
    }

    if (line.trim().startsWith("```")) {
      const language = line.trim().slice(3).trim();
      const code: string[] = [];
      index += 1;
      while (index < lines.length && !(lines[index] ?? "").trim().startsWith("```")) {
        code.push(lines[index] ?? "");
        index += 1;
      }
      index += 1;
      blocks.push(
        <div key={`code-${index}`} className="ov-code-block my-4 overflow-hidden rounded-xl border">
          {language && <div className="ov-code-block-label border-b px-4 py-2 text-xs">{language}</div>}
          <pre className="ov-code-block-content overflow-x-auto p-4 text-sm leading-6"><code>{code.join("\n")}</code></pre>
        </div>,
      );
      continue;
    }

    const heading = line.match(/^(#{1,4})\s+(.+)$/);
    if (heading) {
      const level = heading[1].length;
      const className = level <= 2 ? "mt-6 mb-2 text-lg font-semibold" : "mt-5 mb-2 font-semibold";
      blocks.push(
        <div key={`heading-${index}`} role="heading" aria-level={level} className={className}>
          <InlineMarkdown text={heading[2]} citations={citations} />
        </div>,
      );
      index += 1;
      continue;
    }

    if (/^\s*[-*]\s+/.test(line)) {
      const items: string[] = [];
      while (index < lines.length && /^\s*[-*]\s+/.test(lines[index] ?? "")) {
        items.push((lines[index] ?? "").replace(/^\s*[-*]\s+/, ""));
        index += 1;
      }
      blocks.push(
        <ul key={`ul-${index}`} className="my-3 list-disc space-y-1 pl-6">
          {items.map((item, itemIndex) => <li key={itemIndex}><InlineMarkdown text={item} citations={citations} /></li>)}
        </ul>,
      );
      continue;
    }

    if (/^\s*\d+\.\s+/.test(line)) {
      const items: string[] = [];
      while (index < lines.length && /^\s*\d+\.\s+/.test(lines[index] ?? "")) {
        items.push((lines[index] ?? "").replace(/^\s*\d+\.\s+/, ""));
        index += 1;
      }
      blocks.push(
        <ol key={`ol-${index}`} className="my-3 list-decimal space-y-1 pl-6">
          {items.map((item, itemIndex) => <li key={itemIndex}><InlineMarkdown text={item} citations={citations} /></li>)}
        </ol>,
      );
      continue;
    }

    if (line.startsWith(">")) {
      const quote: string[] = [];
      while (index < lines.length && (lines[index] ?? "").startsWith(">")) {
        quote.push((lines[index] ?? "").replace(/^>\s?/, ""));
        index += 1;
      }
      blocks.push(
        <blockquote key={`quote-${index}`} className="my-4 border-l-4 border-primary/30 pl-4 text-muted-foreground">
          <InlineMarkdown text={quote.join(" ")} citations={citations} />
        </blockquote>,
      );
      continue;
    }

    if (line.includes("|") && (lines[index + 1] ?? "").match(/^\s*\|?\s*:?-+/)) {
      const tableLines = [line];
      index += 2;
      while (index < lines.length && (lines[index] ?? "").includes("|")) {
        tableLines.push(lines[index] ?? "");
        index += 1;
      }
      const rows = tableLines.map(splitTableRow);
      blocks.push(
        <div key={`table-${index}`} className="my-4 overflow-x-auto rounded-lg border border-border">
          <table className="w-full border-collapse text-left text-sm">
            <thead className="bg-muted"><tr>{rows[0]?.map((cell, cellIndex) => <th key={cellIndex} className="border-b border-border px-3 py-2 font-semibold"><InlineMarkdown text={cell} citations={citations} /></th>)}</tr></thead>
            <tbody>{rows.slice(1).map((row, rowIndex) => <tr key={rowIndex} className="border-b border-border/60 last:border-0">{row.map((cell, cellIndex) => <td key={cellIndex} className="px-3 py-2"><InlineMarkdown text={cell} citations={citations} /></td>)}</tr>)}</tbody>
          </table>
        </div>,
      );
      continue;
    }

    const paragraph = [line.trim()];
    index += 1;
    while (index < lines.length && (lines[index] ?? "").trim()) {
      const next = lines[index] ?? "";
      if (/^(#{1,4})\s+/.test(next) || next.trim().startsWith("```") || /^\s*[-*]\s+/.test(next) || /^\s*\d+\.\s+/.test(next) || next.startsWith(">")) break;
      paragraph.push(next.trim());
      index += 1;
    }
    blocks.push(
      <p key={`p-${index}`} className="my-3 leading-7 text-foreground">
        <InlineMarkdown text={paragraph.join(" ")} citations={citations} />
      </p>,
    );
  }
  return blocks;
}

export function AssistantResponse({
  content,
  citations = [],
  error = false,
}: {
  content: string;
  citations?: ChatCitation[];
  error?: boolean;
}) {
  const t = useTranslations("AssistantResponse");
  const citationsById = new Map(citations.map((citation) => [citation.id, citation]));
  const grouped = Array.from(
    citations.reduce((documents, citation) => {
      const existing = documents.get(citation.document_id) ?? [];
      existing.push(citation);
      documents.set(citation.document_id, existing);
      return documents;
    }, new Map<string, ChatCitation[]>()),
  );

  return (
    <div className={error ? "rounded-xl bg-destructive/10 px-4 py-3 text-destructive" : "text-foreground"}>
      <div className="text-sm sm:text-[15px]">{markdownBlocks(content, citationsById)}</div>
      {grouped.length > 0 && (
        <div className="chat-sources-enter mt-6 border-t border-border pt-4">
          <p className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">{t("sources")}</p>
          <div className="grid gap-2 sm:grid-cols-2">
            {grouped.map(([documentId, documentCitations]) => (
              <Link
                key={documentId}
                href={documentViewerHref(documentId, documentCitations)}
                className="group flex items-center gap-3 rounded-xl border border-border bg-card p-3 transition-colors hover:border-primary/30 hover:bg-primary/5"
              >
                <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground group-hover:bg-card group-hover:text-primary">
                  <FileText className="size-4" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium text-foreground">{documentCitations[0]?.source_name}</span>
                  <span className="block text-xs text-muted-foreground">{t("openCitedSource")}</span>
                </span>
                <ExternalLink className="size-4 shrink-0 text-muted-foreground group-hover:text-primary" />
              </Link>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
