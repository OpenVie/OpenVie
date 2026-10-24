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

/**
 * Pure helpers for the client-side document preview (all open-source, no
 * server round-trip beyond the existing download endpoint).
 *
 * Markdown and HTML documents are rendered inside a sandboxed iframe
 * (scripts disabled); CSV and spreadsheets become plain React tables; PDF
 * uses the browser's native viewer; DOCX renders through docx-preview.
 */

export type PreviewKind =
  | "pdf"
  | "docx"
  | "xlsx"
  | "csv"
  | "markdown"
  | "html"
  | "text"
  | "image"
  | "audio"
  | "video"
  | "unsupported";

const EXTENSION_KIND: Record<string, PreviewKind> = {
  pdf: "pdf",
  docx: "docx",
  doc: "docx",
  xlsx: "xlsx",
  xls: "xlsx",
  csv: "csv",
  md: "markdown",
  markdown: "markdown",
  txt: "text",
  html: "html",
  htm: "html",
  png: "image",
  jpg: "image",
  jpeg: "image",
  webp: "image",
  mp3: "audio",
  wav: "audio",
  m4a: "audio",
  oga: "audio",
  ogg: "audio",
  opus: "audio",
  flac: "audio",
  audio: "audio",
  mp4: "video",
  mkv: "video",
  mov: "video",
  webm: "video",
  video: "video",
};

/** Kind by the backend DocumentType, falling back to the stored file extension. */
export function resolvePreviewKind(fileName: string, fileType?: string | null): PreviewKind {
  if (fileType) {
    // Backend DocumentType values, e.g. "PDF" / "MARKDOWN", share the
    // extension vocabulary once lowercased.
    const declared = EXTENSION_KIND[fileType.trim().toLowerCase()];
    if (declared) return declared;
  }
  const extension = fileName.split(".").pop()?.toLowerCase() ?? "";
  const byExtension = EXTENSION_KIND[extension];
  return byExtension ?? "unsupported";
}

export function formatTimestamp(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds));
  const mins = Math.floor(total / 60);
  const secs = total % 60;
  return `${String(mins).padStart(2, "0")}:${String(secs).padStart(2, "0")}`;
}

export function citationSeekSeconds(pageNumber: number | null | undefined): number {
  if (!pageNumber || pageNumber <= 1) return 0;
  return (pageNumber - 1) * 60;
}

const AUDIO_EXTENSIONS = [".mp3", ".wav", ".m4a", ".oga", ".ogg", ".opus", ".flac"];
const VIDEO_EXTENSIONS = [".mp4", ".mkv", ".mov", ".webm"];

export function isAudioSource(fileName: string): boolean {
  const lower = fileName.toLowerCase();
  return AUDIO_EXTENSIONS.some((ext) => lower.endsWith(ext));
}

export function isVideoSource(fileName: string): boolean {
  const lower = fileName.toLowerCase();
  return VIDEO_EXTENSIONS.some((ext) => lower.endsWith(ext));
}

export function isMediaSource(fileName: string): boolean {
  return isAudioSource(fileName) || isVideoSource(fileName);
}

// ----------------------------------------------------------------------
// CSV
// ----------------------------------------------------------------------

/** Quote-aware RFC-4180-ish parser; keeps empty rows out of the render. */
export function parseCsv(text: string, delimiter: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let inQuotes = false;

  const pushField = () => {
    row.push(field);
    field = "";
  };
  const pushRow = () => {
    pushField();
    rows.push(row);
    row = [];
  };

  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    if (inQuotes) {
      if (character === '"') {
        if (text[index + 1] === '"') {
          field += '"';
          index += 1;
        } else {
          inQuotes = false;
        }
      } else {
        field += character;
      }
    } else if (character === '"') {
      inQuotes = true;
    } else if (character === delimiter) {
      pushField();
    } else if (character === "\n") {
      pushRow();
    } else if (character !== "\r") {
      field += character;
    }
  }
  if (!inQuotes && (field !== "" || row.length > 0)) {
    pushRow();
  }
  return rows.filter((candidate) => candidate.some((cell) => cell.trim() !== ""));
}

function countOutsideQuotes(line: string, delimiter: string): number {
  let count = 0;
  let inQuotes = false;
  for (const character of line) {
    if (character === '"') inQuotes = !inQuotes;
    else if (character === delimiter && !inQuotes) count += 1;
  }
  return count;
}

/** The delimiter with the most separators on the header line wins. */
export function detectCsvDelimiter(text: string): string {
  const headerLine = text.split("\n", 1)[0] ?? "";
  if (countOutsideQuotes(headerLine, "\t") >= countOutsideQuotes(headerLine, ";")
      && countOutsideQuotes(headerLine, "\t") >= countOutsideQuotes(headerLine, ",")
      && headerLine.includes("\t")) {
    return "\t";
  }
  if (countOutsideQuotes(headerLine, ";") >= countOutsideQuotes(headerLine, ",")
      && headerLine.includes(";")) {
    return ";";
  }
  return ",";
}

// ----------------------------------------------------------------------
// Markdown / HTML → sandboxed iframe documents
// ----------------------------------------------------------------------

export function escapeHtml(text: string): string {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function inlineMarkdown(text: string): string {
  let html = escapeHtml(text);
  html = html.replaceAll(/\*\*(.+?)\*\*/gu, "<strong>$1</strong>");
  html = html.replaceAll(/\*(.+?)\*/gu, "<em>$1</em>");
  html = html.replaceAll(/`([^`]+)`/gu, "<code>$1</code>");
  html = html.replaceAll(/\[([^\]]+)\]\((https?:\/\/[^)\s]+|mailto:[^)\s]+)\)/gu,
    '<a href="$2" rel="noopener noreferrer">$1</a>');
  return html;
}

/**
 * The subset the indexed-data viewer also presents: fenced code, ATX
 * headings, quotes, lists, pipe tables, paragraphs and the inline tricks.
 * Content is escaped first, so raw HTML in a markdown document stays text.
 */
export function markdownToHtml(markdown: string): string {
  const lines = markdown.replace(/\r\n/g, "\n").split("\n");
  const blocks: string[] = [];
  let index = 0;

  while (index < lines.length) {
    const line = lines[index] ?? "";

    if (/^```/.test(line)) {
      index += 1;
      const code: string[] = [];
      while (index < lines.length && !/^```/.test(lines[index] ?? "")) {
        code.push(lines[index] ?? "");
        index += 1;
      }
      index = Math.min(index + 1, lines.length);
      blocks.push(`<pre class="code"><code>${escapeHtml(code.join("\n"))}</code></pre>`);
      continue;
    }

    const heading = line.match(/^(#{1,6})\s+(.*)$/);
    if (heading) {
      const level = heading[1].length;
      blocks.push(`<h${level}>${inlineMarkdown(heading[2])}</h${level}>`);
      index += 1;
      continue;
    }

    const quote = line.match(/^>\s?(.*)$/);
    if (quote) {
      const quoted: string[] = [quote[1] ?? ""];
      index += 1;
      while (index < lines.length) {
        const next = lines[index]?.match(/^>\s?(.*)$/);
        if (!next) break;
        quoted.push(next[1] ?? "");
        index += 1;
      }
      blocks.push(`<blockquote>${quoted.map((entry) => inlineMarkdown(entry)).join("<br/>")}</blockquote>`);
      continue;
    }

    if (/^([-*+])\s+/.test(line)) {
      const items: string[] = [];
      while (index < lines.length && /^([-*+])\s+/.test(lines[index] ?? "")) {
        items.push(inlineMarkdown((lines[index] ?? "").replace(/^([-*+])\s+/, "")));
        index += 1;
      }
      blocks.push(`<ul>${items.map((entry) => `<li>${entry}</li>`).join("")}</ul>`);
      continue;
    }

    if (/^\d+[.)]\s+/.test(line)) {
      const items: string[] = [];
      while (index < lines.length && /^\d+[.)]\s+/.test(lines[index] ?? "")) {
        items.push(inlineMarkdown((lines[index] ?? "").replace(/^\d+[.)]\s+/, "")));
        index += 1;
      }
      blocks.push(`<ol>${items.map((entry) => `<li>${entry}</li>`).join("")}</ol>`);
      continue;
    }

    if (/^\s*\|.+\|\s*$/.test(line)) {
      const tableLines: string[] = [];
      while (index < lines.length && /^\s*\|.+\|\s*$/.test(lines[index] ?? "")) {
        tableLines.push(lines[index] ?? "");
        index += 1;
      }
      const cells = tableLines.map((entry) => entry.trim()
        .replace(/^\|/, "")
        .replace(/\|$/, "")
        .split("|")
        .map((cell) => inlineMarkdown(cell.trim())));
      const head = cells.shift() ?? [];
      const separator = cells.shift() ?? [];
      const isHeader = head.length > 0 && separator.every((cell) => /^:?-{3,}:?$/.test(cell.replace(/<[^>]*>/g, "").trim()));
      const body = isHeader ? cells : [head, ...cells];
      const headRow = (isHeader ? head : separator);
      blocks.push(
        `<table><thead><tr>${headRow.map((cell) => `<th>${cell}</th>`).join("")}</tr></thead>`
        + `<tbody>${body.map((entry) => `<tr>${entry.map((cell) => `<td>${cell}</td>`).join("")}</tr>`).join("")}</tbody></table>`,
      );
      continue;
    }

    if (line.trim() === "") {
      index += 1;
      continue;
    }

    const paragraph: string[] = [];
    while (index < lines.length && (lines[index] ?? "").trim() !== ""
        && !/^(#{1,6}\s|>|\s*([-*+]|\d+[.)])\s|\s*\|)/.test(lines[index] ?? "")) {
      paragraph.push(lines[index] ?? "");
      index += 1;
    }
    blocks.push(`<p>${inlineMarkdown(paragraph.join(" "))}</p>`);
  }
  return blocks.join("\n");
}

/** Base typography for markdown/iframe documents, mirrored from the app look. */
export const PREVIEW_STYLES = `
  body { margin: 0; padding: 24px 28px; color: #1a1a1a; background: #ffffff;
    font-family: ui-sans-serif, system-ui, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    font-size: 15px; line-height: 1.65; word-wrap: break-word; }
  h1, h2, h3, h4, h5, h6 { line-height: 1.3; margin: 28px 0 12px; font-weight: 650; }
  h1 { font-size: 26px; } h2 { font-size: 22px; } h3 { font-size: 18px; }
  p { margin: 12px 0; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    background: #f4f4f5; padding: 1px 6px; border-radius: 6px; font-size: 13px; }
  pre.code { background: #f4f4f5; border-radius: 12px; padding: 14px 16px; overflow-x: auto; }
  pre.code code { background: transparent; padding: 0; }
  blockquote { margin: 16px 0; padding: 8px 16px; border-left: 3px solid #a3e635;
    color: #52525b; background: #fafafa; border-radius: 0 10px 10px 0; }
  table { border-collapse: collapse; width: 100%; margin: 16px 0; font-size: 14px; }
  th, td { border: 1px solid #e4e4e7; padding: 8px 12px; text-align: left; vertical-align: top; }
  th { background: #f4f4f5; font-weight: 620; }
  a { color: #4d7c0f; }
  ul, ol { padding-left: 26px; }
  li { margin: 6px 0; }
  hr { border: none; border-top: 1px solid #e4e4e7; margin: 24px 0; }
  /* Evidence anchoring (same look as the app-side preview-citation rules). */
  mark.preview-citation {
    background: rgb(253 230 138 / .55);
    box-shadow: 0 0 0 2px rgb(234 179 8 / .4);
    border-radius: 4px;
    color: inherit;
  }
`;

/**
 * Wraps rendered markdown (already escaped HTML) or an HTML source document
 * into an iframe `srcDoc`. The consumer renders it with `sandbox=""`, so
 * scripts inside cannot execute and the payload is inert.
 */
export function textDocumentHtml(sourceHtml: string, base: string): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>${base}</title><style>${PREVIEW_STYLES}</style></head><body>${sourceHtml}</body></html>`;
}
