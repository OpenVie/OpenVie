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

"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { AlertTriangle, Download, Loader2 } from "lucide-react";
import type { PDFDocumentLoadingTask } from "pdfjs-dist";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useApiClient } from "@/hooks/useApiClient";
import { downloadDocumentApi } from "@/lib/documents-api";
import {
  detectCsvDelimiter,
  markdownToHtml,
  parseCsv,
  resolvePreviewKind,
  textDocumentHtml,
  type PreviewKind,
} from "@/lib/preview-renderers";
import {
  cellRangeRows,
  clearPreviewMarks,
  findNormalizedRange,
  findSourceRange,
  markTextRange,
  pdfItemIntervals,
  pdfRectsForIntervals,
  pickMatchingRows,
  projectPdfItems,
  projectTextNodes,
  type PdfRect,
  type PdfTextItem,
} from "@/lib/preview-citations";
import type { DocumentUnit } from "@/types";

const MAX_ROWS = 500;
const PDF_RENDER_MARGIN = "1200px 0px";

async function importPdf(): Promise<typeof import("pdfjs-dist")> {
  const pdfjs = await import("pdfjs-dist");
  // The worker is vendored to `public/` and kept in sync with the installed
  // pdfjs-dist version — no third-party CDN, still fully open source.
  pdfjs.GlobalWorkerOptions.workerSrc = "/pdf.worker.min.mjs";
  return pdfjs;
}

type SheetTable = { name: string; rows: string[][]; truncated: boolean };

type PreviewData =
  | { kind: "pdf"; buffer: ArrayBuffer }
  | { kind: "docx"; buffer: ArrayBuffer }
  | { kind: "xlsx"; sheets: SheetTable[] }
  | { kind: "csv"; rows: string[][]; truncated: boolean }
  | { kind: "markdown" | "html"; srcDoc: string }
  | { kind: "text"; text: string }
  | { kind: "image"; url: string }
  | { kind: "unsupported" };

/** Evidence identity for effects: stable per unit, changes re-anchor the highlight. */
type EvidenceAnchor = { key: string; unit: DocumentUnit };

function evidenceAnchor(unit: DocumentUnit | null | undefined): EvidenceAnchor | null {
  if (!unit) return null;
  return { key: `${unit.unit_id ?? "chunk"}:${unit.chunk_index}`, unit };
}

function cellText(value: unknown): string {
  if (value === null || value === undefined) return "";
  if (value instanceof Date) return value.toISOString().slice(0, 19).replace("T", " ");
  return String(value);
}

type HighlightStyle = "row" | "cell";

/** Renders one plain table with the first row as header and evidence rows marked. */
function DataTable({
  rows,
  title,
  className,
  highlightRows = [],
  highlightStyle = "row",
}: {
  rows: string[][];
  title?: string;
  className?: string;
  /** Indices into `rows` (0 = header row) to mark. */
  highlightRows?: number[];
  highlightStyle?: HighlightStyle;
}) {
  const columnCount = useMemo(
    () => rows.reduce((max, row) => Math.max(max, row.length), 0),
    [rows],
  );
  const rowRefs = useRef(new Map<number, HTMLTableRowElement>());
  const highlighted = useMemo(() => new Set(highlightRows), [highlightRows]);

  useEffect(() => {
    const first = highlightRows[0];
    if (first === undefined) return;
    rowRefs.current.get(first)?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [highlightRows]);

  if (rows.length === 0) return null;
  const [head, ...body] = rows;
  return (
    <div className={cn("overflow-auto rounded-lg border border-border/70 bg-background", className)}>
      <table className="w-full border-collapse text-left text-xs sm:text-sm">
        {title && <caption className="sr-only">{title}</caption>}
        <thead className="sticky top-0 z-10 bg-muted/95 backdrop-blur">
          <tr>
            {Array.from({ length: columnCount }, (_, index) => (
              <th key={index} className="border-b border-border px-3 py-2 font-semibold">{head?.[index] ?? ""}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {body.map((row, rowIndex) => {
            // Body index i corresponds to physical row i+1 of `rows`.
            const physical = rowIndex + 1;
            const isHighlighted = highlighted.has(physical);
            return (
              <tr
                key={rowIndex}
                ref={(element) => {
                  if (element) rowRefs.current.set(physical, element);
                  else rowRefs.current.delete(physical);
                }}
                className={cn(
                  "border-b border-border/50 last:border-0",
                  isHighlighted && highlightStyle === "row" && "bg-amber-200/60 ring-2 ring-inset ring-amber-400",
                  !isHighlighted && "odd:bg-muted/30",
                )}
              >
                {Array.from({ length: columnCount }, (_, index) => (
                  <td
                    key={index}
                    className={cn(
                      "max-w-80 break-words px-3 py-1.5 align-top",
                      isHighlighted && highlightStyle === "cell" && "bg-amber-200/60",
                    )}
                  >
                    {row[index] ?? ""}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** TXT: match the chunk inside the raw preview text and render it as a React mark. */
function TextPane({ text, anchor }: { text: string; anchor: EvidenceAnchor | null }) {
  const range = useMemo(
    () => (anchor
      ? findSourceRange(text, anchor.unit.text, {
        sourceStart: anchor.unit.source_start,
        sourceEnd: anchor.unit.source_end,
      })
      : null),
    // Anchor once per unit; object churn would re-run without a change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [text, anchor?.key],
  );
  const markRef = useRef<HTMLElement | null>(null);
  const rangeId = range ? `${range.start}:${range.end}` : "";

  useEffect(() => {
    if (range && rangeId) markRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [range, rangeId]);

  if (!range) {
    return <pre className="whitespace-pre-wrap break-words font-mono text-xs leading-5 text-foreground sm:text-sm">{text}</pre>;
  }
  return (
    <pre className="whitespace-pre-wrap break-words font-mono text-xs leading-5 text-foreground sm:text-sm">
      {text.slice(0, range.start)}
      <mark ref={markRef} data-preview-citation="true" className="preview-citation">
        {text.slice(range.start, range.end)}
      </mark>
      {text.slice(range.end)}
    </pre>
  );
}

/** DOCX: the container mounts first, docx-preview fills it, then evidence is marked. */
function DocxPane({
  buffer,
  className,
  onError,
  anchor,
}: {
  buffer: ArrayBuffer;
  className?: string;
  onError: () => void;
  anchor: EvidenceAnchor | null;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [renderedTick, setRenderedTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    const container = containerRef.current;
    if (!container) return;
    (async () => {
      try {
        const { renderAsync } = await import("docx-preview");
        if (cancelled) return;
        container.innerHTML = "";
        await renderAsync(buffer, container, undefined, {
          inWrapper: true,
          ignoreWidth: false,
          ignoreHeight: true,
          useBase64URL: true,
          renderHeaders: true,
          renderFooters: true,
        });
        if (!cancelled) setRenderedTick((tick) => tick + 1);
      } catch {
        if (!cancelled) onError();
      }
    })();
    return () => {
      cancelled = true;
      container.innerHTML = "";
    };
  }, [buffer, onError]);

  useEffect(() => {
    const root = containerRef.current;
    const owner = root?.ownerDocument;
    if (!root || !owner) return;
    clearPreviewMarks(root);
    if (!anchor) return;
    const projection = projectTextNodes(root, owner);
    if (!projection) return;
    const range = findNormalizedRange(projection.source.normalized, anchor.unit.text);
    if (!range) return;
    const marks = markTextRange(projection, range, owner);
    marks[0]?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [anchor, renderedTick]);

  return (
    <div className={cn("overflow-auto", className)}>
      <div ref={containerRef} className="mx-auto min-h-40 bg-white text-foreground" />
    </div>
  );
}

/**
 * Markdown/HTML: sanitized srcDoc frame. `sandbox="allow-same-origin"` keeps
 * embedded scripts inert yet lets this pane reach the frame once loaded and
 * mark + scroll the evidence inside it.
 */
function SandboxedPane({
  srcDoc,
  className,
  anchor,
}: {
  srcDoc: string;
  className?: string;
  anchor: EvidenceAnchor | null;
}) {
  const frameRef = useRef<HTMLIFrameElement | null>(null);
  const [loadTick, setLoadTick] = useState(0);

  useEffect(() => {
    const root = frameRef.current?.contentDocument?.body;
    const owner = root?.ownerDocument;
    if (!root || !owner) return;
    clearPreviewMarks(root);
    if (!anchor) return;
    const projection = projectTextNodes(root, owner);
    if (!projection) return;
    const range = findNormalizedRange(projection.source.normalized, anchor.unit.text);
    if (!range) return;
    const marks = markTextRange(projection, range, owner);
    marks[0]?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [anchor, loadTick]);

  return (
    <iframe
      ref={frameRef}
      srcDoc={srcDoc}
      title="preview"
      sandbox="allow-same-origin"
      onLoad={() => setLoadTick((tick) => tick + 1)}
      className={cn("h-full min-h-56 w-full flex-1 border-0 bg-white", className)}
    />
  );
}

/** The sheet index an evidence unit anchors to (name hint, else the first sheet). */
function selectSheetIndex(sheets: SheetTable[], unit: DocumentUnit | null): number {
  const wanted = unit?.sheet_name?.trim().toLowerCase();
  if (wanted) {
    const found = sheets.findIndex((sheet) => sheet.name.trim().toLowerCase() === wanted);
    if (found >= 0) return found;
  }
  return 0;
}

/** Evidence rows for the selected sheet: the A1 cell-range hint, else a loose text match. */
function sheetHighlightRows(sheets: SheetTable[], sheetIndex: number, unit: DocumentUnit | null): number[] {
  const sheet = sheets[sheetIndex];
  if (!sheet || !unit) return [];
  const range = unit.sheet_name?.trim() ? cellRangeRows(unit.cell_range) : null;
  if (range) {
    // A1 rows are 1-based; `sheet.rows` is zero-based in the same order.
    const rows: number[] = [];
    for (let row = Math.max(range.first, 1); row <= range.last && row <= MAX_ROWS + 1; row += 1) {
      rows.push(row - 1);
    }
    return rows;
  }
  return pickMatchingRows(sheet.rows, unit.text);
}

/** XLSX: sheet chosen by the evidence sheet hint; rows marked by cell-range hint or loose text match. */
function XlsxPane({ sheets, fileName, anchor }: { sheets: SheetTable[]; fileName: string; anchor: EvidenceAnchor | null }) {
  const t = useTranslations("DocumentPreview");
  const unit = anchor?.unit ?? null;
  const hintSheetIndex = useMemo(() => selectSheetIndex(sheets, unit), [sheets, unit]);
  const hintRows = useMemo(() => sheetHighlightRows(sheets, hintSheetIndex, unit), [sheets, hintSheetIndex, unit]);
  const [userSheetIndex, setUserSheetIndex] = useState<number | null>(null);
  // Render-time adjustment: when the evidence hint changes, a user-made sheet
  // selection is stale and falls back to the hinted sheet.
  const [prevHintIndex, setPrevHintIndex] = useState(hintSheetIndex);
  if (prevHintIndex !== hintSheetIndex) {
    setPrevHintIndex(hintSheetIndex);
    setUserSheetIndex(null);
  }
  const activeIndex = userSheetIndex ?? hintSheetIndex;
  const sheet = sheets[activeIndex];

  return (
    <div className="min-w-0 space-y-3">
      {sheets.length > 1 && (
        <div className="flex flex-wrap gap-1.5" role="tablist" aria-label={t("sheetAria")}>
          {sheets.map((entry, index) => (
            <button
              key={entry.name + index}
              type="button"
              role="tab"
              aria-selected={index === activeIndex}
              className={cn(
                "rounded-full border px-3 py-1 text-xs font-medium transition-colors",
                index === activeIndex
                  ? "border-primary bg-primary text-primary-foreground"
                  : "border-border bg-background text-muted-foreground hover:bg-muted",
              )}
              onClick={() => setUserSheetIndex(index)}
            >
              {entry.name}
            </button>
          ))}
        </div>
      )}
      {sheet && <DataTable rows={sheet.rows} title={`${fileName} · ${sheet.name}`} highlightRows={activeIndex === hintSheetIndex ? hintRows : []} />}
      {sheet?.truncated && <p className="text-xs text-muted-foreground">{t("truncated")}</p>}
    </div>
  );
}

/** CSV: rows matched against the evidence text with the loose key. */
function CsvPane({ rows, fileName, anchor }: { rows: string[][]; fileName: string; anchor: EvidenceAnchor | null }) {
  const highlightRows = useMemo(
    () => (anchor ? pickMatchingRows(rows, anchor.unit.text) : []),
    [rows, anchor],
  );
  return <DataTable rows={rows} title={fileName} highlightRows={highlightRows} />;
}

type PdfPageInfo = { width: number; height: number };

/**
 * PDF through pdf.js: pages render lazily via IntersectionObserver, and the
 * evidence unit draws proportional highlight rects over its text layer.
 */
function PdfPane({
  buffer,
  className,
  onError,
  anchor,
}: {
  buffer: ArrayBuffer;
  className?: string;
  onError: () => void;
  anchor: EvidenceAnchor | null;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const canvasRefs = useRef(new Map<number, HTMLCanvasElement>());
  const pageRefs = useRef(new Map<number, HTMLDivElement>());
  const pageInfo = useRef(new Map<number, PdfPageInfo>());
  const docRef = useRef<Awaited<PDFDocumentLoadingTask["promise"]> | null>(null);
  const taskRef = useRef<PDFDocumentLoadingTask | null>(null);
  const renderedRef = useRef(new Set<number>());
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [pages, setPages] = useState<PdfPageInfo[]>([]);
  const [scale, setScale] = useState(1);
  const [evidence, setEvidence] = useState<{ page: number; rects: PdfRect[] }>({ page: 0, rects: [] });

  // Render-time adjustment: a new buffer or unit resets the pane state before
  // the loaders below run their async work. Refs are only touched in effects.
  const [seenBuffer, setSeenBuffer] = useState(buffer);
  if (seenBuffer !== buffer) {
    setSeenBuffer(buffer);
    setStatus("loading");
    setPages([]);
    setEvidence({ page: 0, rects: [] });
  }
  const seenAnchorKey = anchor?.key ?? "";
  const [anchorSeen, setAnchorSeen] = useState(seenAnchorKey);
  if (anchorSeen !== seenAnchorKey) {
    setAnchorSeen(seenAnchorKey);
    setEvidence({ page: 0, rects: [] });
  }

  useEffect(() => {
    let cancelled = false;
    renderedRef.current.clear();
    canvasRefs.current.clear();
    pageRefs.current.clear();
    pageInfo.current.clear();
    taskRef.current = null;
    docRef.current = null;

    (async () => {
      try {
        const pdfjs = await importPdf();
        if (cancelled) return;
        const task = pdfjs.getDocument({ data: buffer.slice(0) });
        taskRef.current = task;
        const doc = await task.promise;
        if (cancelled) {
          void task.destroy();
          taskRef.current = null;
          return;
        }
        docRef.current = doc;
        const infos: PdfPageInfo[] = [];
        for (let pageNo = 1; pageNo <= doc.numPages; pageNo += 1) {
          const page = await doc.getPage(pageNo);
          const viewport = page.getViewport({ scale: 1 });
          const info = { width: viewport.width, height: viewport.height };
          pageInfo.current.set(pageNo, info);
          infos.push(info);
        }
        setPages(infos);
        setStatus("ready");
      } catch {
        if (!cancelled) onError();
      }
    })();

    return () => {
      cancelled = true;
      void taskRef.current?.destroy();
      taskRef.current = null;
      docRef.current = null;
    };
  }, [buffer, onError]);

  // Fit the first page's width to the pane; the scale is shared by every page.
  const fitScale = useCallback(() => {
    const first = pageInfo.current.get(1);
    const container = containerRef.current;
    if (!first || !container || first.width <= 0) return;
    setScale(Math.max((container.clientWidth - 24) / first.width, 0.2));
  }, []);

  useEffect(() => {
    fitScale();
    const onResize = () => fitScale();
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, [pages, fitScale]);

  const renderPage = useCallback((pageNo: number) => {
    const info = pageInfo.current.get(pageNo);
    const canvas = canvasRefs.current.get(pageNo);
    const doc = docRef.current;
    if (!info || !canvas || !doc || renderedRef.current.has(pageNo)) return;
    renderedRef.current.add(pageNo);
    (async () => {
      try {
        const page = await doc.getPage(pageNo);
        const dpr = window.devicePixelRatio || 1;
        const viewport = page.getViewport({ scale });
        canvas.width = Math.floor(viewport.width);
        canvas.height = Math.floor(viewport.height);
        canvas.style.width = `${Math.floor(viewport.width / dpr)}px`;
        canvas.style.height = `${Math.floor(viewport.height / dpr)}px`;
        await page.render({ canvas, viewport }).promise;
      } catch {
        // Allow the next intersection to retry the page.
        renderedRef.current.delete(pageNo);
      }
    })();
  }, [scale]);

  useEffect(() => {
    if (status !== "ready") return;
    const container = containerRef.current;
    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          const pageNo = Number((entry.target as HTMLElement).dataset.page ?? "0");
          renderedRef.current.delete(pageNo); // re-render so a scale change repaints
          renderPage(pageNo);
        });
      },
      { root: null, rootMargin: PDF_RENDER_MARGIN },
    );
    container?.querySelectorAll("[data-page]").forEach((element) => observer.observe(element));
    return () => observer.disconnect();
  }, [status, pages, renderPage]);

  // Anchor the evidence: hint page first, then a linear scan of the text layer.
  // (The reset to an empty overlay is done in the anchorSeen render adjustment.)
  useEffect(() => {
    if (status !== "ready") return;
    const unit = anchor?.unit;
    const doc = docRef.current;
    if (!unit || !doc) return;
    let cancelled = false;
    (async () => {
      const hint = unit.page_number != null && unit.page_number >= 1 && unit.page_number <= doc.numPages
        ? unit.page_number
        : null;
      const candidates: number[] = [];
      if (hint) candidates.push(hint);
      for (let pageNo = 1; pageNo <= doc.numPages; pageNo += 1) {
        if (pageNo !== hint) candidates.push(pageNo);
      }
      for (const pageNo of candidates) {
        try {
          const page = await doc.getPage(pageNo);
          const content = await page.getTextContent();
          const items = (content.items ?? []) as PdfTextItem[];
          const projection = projectPdfItems(items);
          const range = findNormalizedRange(projection.joined, unit.text);
          if (!range) continue;
          const intervals = pdfItemIntervals(projection, range);
          if (intervals.length === 0) continue;
          const info = pageInfo.current.get(pageNo);
          if (!info) continue;
          if (cancelled) return;
          setEvidence({ page: pageNo, rects: pdfRectsForIntervals(items, intervals, scale, info.height) });
          pageRefs.current.get(pageNo)?.scrollIntoView({ behavior: "smooth", block: "start" });
          return;
        } catch {
          // A malformed text layer on one page must not stop the scan.
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [anchor, status, scale]);

  if (status === "loading") {
    return (
      <div className={cn("grid place-content-center place-items-center", className)}>
        <Loader2 className="size-6 animate-spin text-primary" />
      </div>
    );
  }

  return (
    <div ref={containerRef} className={cn("h-full overflow-auto bg-muted/60 p-2 sm:p-4", className)}>
      {pages.map((info, index) => {
        const pageNo = index + 1;
        return (
          <div
            key={pageNo}
            data-page={pageNo}
            ref={(element) => {
              if (element) pageRefs.current.set(pageNo, element);
              else pageRefs.current.delete(pageNo);
            }}
            className="relative mx-auto mb-3 bg-white shadow-md"
            style={{ width: Math.floor(info.width * scale), height: Math.floor(info.height * scale) }}
          >
            <canvas
              ref={(element) => {
                if (element) canvasRefs.current.set(pageNo, element);
                else canvasRefs.current.delete(pageNo);
              }}
            />
            <div className="pointer-events-none absolute inset-0 overflow-hidden">
              {evidence.page === pageNo && evidence.rects.map((rect, rectIndex) => (
                <span
                  key={rectIndex}
                  className="preview-citation-overlay absolute rounded-sm"
                  style={{ left: rect.x, top: rect.y, width: rect.width, height: rect.height }}
                />
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}

export type DocumentPreviewProps = {
  documentId: string;
  fileName: string;
  fileType?: string | null;
  className?: string;
  /** The citation ("bằng chứng") unit to anchor inside the original preview. */
  evidence?: DocumentUnit | null;
};

/**
 * Client-side preview of the original document file: PDF via pdf.js with an
 * evidence overlay, DOCX renderer with inline marks, spreadsheets/CSV with
 * evidence rows, and text/markdown/HTML with the chunk marked inline.
 */
export default function DocumentPreview({ documentId, fileName, fileType, className, evidence }: DocumentPreviewProps) {
  const t = useTranslations("DocumentPreview");
  const { request } = useApiClient();
  const [stage, setStage] = useState<"loading" | "ready" | "error">("loading");
  const [data, setData] = useState<PreviewData | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const blobRef = useRef<Blob | null>(null);
  const imageUrlRef = useRef<string | null>(null);
  const anchor = useMemo(
    () => evidenceAnchor(evidence ?? null),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [evidence?.unit_id, evidence?.chunk_index],
  );
  const failure = useCallback(() => setStage("error"), []);

  // Render-time adjustment (React "you might not need an effect"): resetting
  // the loading state when the target document or the retry counter changes.
  const [seenLoad, setSeenLoad] = useState({ documentId, reloadKey });
  if (seenLoad.documentId !== documentId || seenLoad.reloadKey !== reloadKey) {
    setSeenLoad({ documentId, reloadKey });
    setStage("loading");
    setData(null);
  }

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const downloaded = await downloadDocumentApi(request, documentId);
        if (cancelled) return;
        blobRef.current = downloaded.blob;
        const kind: PreviewKind = resolvePreviewKind(downloaded.fileName || fileName, fileType);

        if (kind === "pdf" || kind === "docx") {
          const buffer = await downloaded.blob.arrayBuffer();
          if (cancelled) return;
          setData(kind === "pdf" ? { kind: "pdf", buffer } : { kind: "docx", buffer });
        } else if (kind === "xlsx") {
          const { default: readXlsxFile } = await import("read-excel-file/browser");
          const parsed = await readXlsxFile(await downloaded.blob.arrayBuffer());
          if (cancelled) return;
          const sheets: SheetTable[] = parsed.map((entry) => {
            const allRows = Array.isArray(entry.data) ? entry.data : [];
            return {
              name: entry.sheet,
              rows: allRows.slice(0, MAX_ROWS).map((row) => (Array.isArray(row) ? row.map((cell) => cellText(cell)) : [])),
              truncated: allRows.length > MAX_ROWS,
            };
          });
          setData({ kind: "xlsx", sheets });
        } else if (kind === "csv") {
          const text = await downloaded.blob.text();
          if (cancelled) return;
          const parsed = parseCsv(text, detectCsvDelimiter(text));
          setData({ kind: "csv", rows: parsed.slice(0, MAX_ROWS), truncated: parsed.length > MAX_ROWS });
        } else if (kind === "markdown" || kind === "html") {
          const text = await downloaded.blob.text();
          if (cancelled) return;
          const bodyHtml = kind === "markdown" ? markdownToHtml(text) : text;
          setData({ kind, srcDoc: textDocumentHtml(bodyHtml, fileName) });
        } else if (kind === "text") {
          const text = await downloaded.blob.text();
          if (cancelled) return;
          setData({ kind: "text", text });
        } else if (kind === "image") {
          if (imageUrlRef.current) URL.revokeObjectURL(imageUrlRef.current);
          const url = URL.createObjectURL(downloaded.blob);
          if (cancelled) {
            URL.revokeObjectURL(url);
            return;
          }
          imageUrlRef.current = url;
          setData({ kind: "image", url });
        } else {
          setData({ kind: "unsupported" });
        }
        if (!cancelled) setStage("ready");
      } catch {
        if (!cancelled) setStage("error");
      }
    })();

    return () => {
      cancelled = true;
      blobRef.current = null;
      if (imageUrlRef.current) {
        URL.revokeObjectURL(imageUrlRef.current);
        imageUrlRef.current = null;
      }
    };
  }, [documentId, fileName, fileType, request, reloadKey]);

  function saveAs() {
    const blob = blobRef.current;
    if (!blob) return;
    const url = URL.createObjectURL(blob);
    const anchorElement = window.document.createElement("a");
    anchorElement.href = url;
    anchorElement.download = fileName;
    anchorElement.click();
    URL.revokeObjectURL(url);
  }

  function reload() {
    setStage("loading");
    setData(null);
    setReloadKey((key) => key + 1);
  }

  const shell = cn(
    "relative flex min-h-56 w-full min-w-0 flex-col overflow-hidden rounded-xl border border-border bg-muted/40",
    className,
  );

  if (stage === "loading") {
    return (
      <div className={cn(shell, "place-content-center place-items-center px-6 py-12 text-center")}>
        <Loader2 className="size-6 animate-spin text-primary" />
        <p className="mt-3 text-sm text-muted-foreground">{t("loading")}</p>
      </div>
    );
  }

  if (stage === "error") {
    return (
      <div className={cn(shell, "place-content-center place-items-center px-6 py-12 text-center")}>
        <AlertTriangle className="size-6 text-amber-500" />
        <p className="mt-3 text-sm text-muted-foreground">{t("loadError")}</p>
        <Button size="sm" variant="outline" className="mt-4" onClick={reload}>
          {t("retry")}
        </Button>
      </div>
    );
  }

  if (!data || data.kind === "unsupported") {
    return (
      <div className={cn(shell, "place-content-center place-items-center px-6 py-12 text-center")}>
        <p className="text-sm text-muted-foreground">{t("unsupported")}</p>
        <Button size="sm" variant="outline" className="mt-4" onClick={saveAs}>
          <Download className="size-4" /> {t("saveAs")}
        </Button>
      </div>
    );
  }

  return (
    <div className={shell}>
      {data.kind === "pdf" && <PdfPane buffer={data.buffer} className="flex-1" anchor={anchor} onError={failure} />}
      {data.kind === "docx" && <DocxPane buffer={data.buffer} className="flex-1 bg-white p-4" anchor={anchor} onError={failure} />}
      {data.kind === "xlsx" && (
        <div className="flex-1 overflow-auto p-3 sm:p-4">
          <XlsxPane sheets={data.sheets} fileName={fileName} anchor={anchor} />
        </div>
      )}
      {data.kind === "csv" && (
        <div className="flex-1 overflow-auto p-3 sm:p-4">
          {data.rows.length === 0
            ? <p className="grid min-h-32 place-items-center text-sm text-muted-foreground">{t("empty")}</p>
            : <CsvPane rows={data.rows} fileName={fileName} anchor={anchor} />}
          {data.truncated && data.rows.length > 0 && <p className="mt-2 text-xs text-muted-foreground">{t("truncated")}</p>}
        </div>
      )}
      {(data.kind === "markdown" || data.kind === "html") && (
        <SandboxedPane srcDoc={data.srcDoc} className="flex-1" anchor={anchor} />
      )}
      {data.kind === "text" && (
        <div className="flex-1 overflow-auto p-3 sm:p-4">
          <TextPane text={data.text} anchor={anchor} />
        </div>
      )}
      {data.kind === "image" && (
        <div className="flex-1 overflow-auto bg-white p-3 sm:p-4">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={data.url} alt={fileName} className="mx-auto max-w-full rounded-lg" />
        </div>
      )}
    </div>
  );
}
