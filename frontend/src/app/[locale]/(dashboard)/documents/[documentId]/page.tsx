"use client";


import { useEffect, useMemo, useRef, useState } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { useParams, useSearchParams } from "next/navigation";
import { useRouter } from "@/i18n/navigation";
import toast from "react-hot-toast";
import {
  ArrowLeft,
  ChevronLeft,
  ChevronRight,
  Code2,
  Download,
  FileText,
  Loader2,
  Sheet,
  Table2,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { useApiClient } from "@/hooks/useApiClient";
import {
  downloadDocumentApi,
  getDocumentStatusApi,
  getDocumentUnitsApi,
  isTerminalDocumentStatus,
} from "@/lib/documents-api";
import { cn } from "@/lib/utils";
import type { DocumentStatusResponse, DocumentUnit } from "@/types";

function unitIdentifier(unit: DocumentUnit): string {
  return unit.unit_id ? `unit:${unit.unit_id}` : `chunk:${unit.chunk_index}`;
}

function tableRows(text: string): string[][] {
  const lines = text
    .split("\n")
    .filter((line) => line.trim())
    .map((line) => line.replace(/^\||\|$/g, "").split("|").map((cell) => cell.trim()))
    .filter((row) => !row.every((cell) => /^:?-{3,}:?$/.test(cell)));
  if (lines.length !== 1) return lines;

  const cells = lines[0] ?? [];
  for (let columnCount = 2; columnCount <= Math.min(16, cells.length); columnCount += 1) {
    const boundaryStep = columnCount - 1;
    if ((cells.length - 1) % boundaryStep !== 0) continue;
    const boundaryIndexes: number[] = [];
    for (let index = boundaryStep; index < cells.length - 1; index += boundaryStep) {
      boundaryIndexes.push(index);
    }
    if (boundaryIndexes.length === 0) continue;
    const boundaries = boundaryIndexes.map((index) => splitFlattenedBoundary(cells[index] ?? ""));
    if (boundaries.some((boundary) => boundary === null)) continue;

    const reconstructed: string[][] = [];
    let row: string[] = [];
    for (let index = 0; index < cells.length; index += 1) {
      const boundaryPosition = boundaryIndexes.indexOf(index);
      if (boundaryPosition >= 0) {
        const boundary = boundaries[boundaryPosition]!;
        row.push(boundary[0]);
        reconstructed.push(row);
        row = [boundary[1]];
      } else {
        row.push(cells[index] ?? "");
      }
    }
    reconstructed.push(row);
    if (reconstructed.every((candidate) => candidate.length === columnCount)) {
      return reconstructed;
    }
  }
  return lines;
}

function splitFlattenedBoundary(value: string): [string, string] | null {
  const match = /\s+(?=\p{Lu})/u.exec(value);
  if (!match || match.index <= 0) return null;
  const left = value.slice(0, match.index).trim();
  const right = value.slice(match.index).trim();
  return left && right ? [left, right] : null;
}

function spreadsheetFields(text: string, fallbackLabel: string): Array<[string, string]> {
  return text
    .split(";")
    .map((part) => part.trim())
    .map((part) => {
      const separator = part.indexOf(":");
      return separator > 0
        ? [part.slice(0, separator).trim(), part.slice(separator + 1).trim()]
        : [fallbackLabel, part];
    });
}

type ViewerBlock =
  | { kind: "unit"; unit: DocumentUnit }
  | {
      kind: "spreadsheet-table";
      tableId: string;
      summary: DocumentUnit | null;
      rows: DocumentUnit[];
    };

function buildViewerBlocks(units: DocumentUnit[]): ViewerBlock[] {
  const tables = new Map<
    string,
    { firstIndex: number; summary: DocumentUnit | null; rows: DocumentUnit[] }
  >();

  units.forEach((unit, index) => {
    if (unit.modality !== "spreadsheet" || !unit.table_id) return;
    if (unit.block_type !== "table" && unit.block_type !== "row") return;
    const table = tables.get(unit.table_id) ?? {
      firstIndex: index,
      summary: null,
      rows: [],
    };
    if (unit.block_type === "table") table.summary = unit;
    else table.rows.push(unit);
    tables.set(unit.table_id, table);
  });

  const blocks: ViewerBlock[] = [];
  units.forEach((unit, index) => {
    const table = unit.table_id ? tables.get(unit.table_id) : undefined;
    if (table && (unit.block_type === "table" || unit.block_type === "row")) {
      if (table.firstIndex === index) {
        if (table.rows.length > 0) {
          blocks.push({
            kind: "spreadsheet-table",
            tableId: unit.table_id!,
            summary: table.summary,
            rows: table.rows,
          });
        } else {
          blocks.push({ kind: "unit", unit });
        }
      }
      return;
    }
    blocks.push({ kind: "unit", unit });
  });
  return blocks;
}

function SpreadsheetTable({
  summary,
  rows,
  activeIdentifier,
  registerUnit,
}: {
  summary: DocumentUnit | null;
  rows: DocumentUnit[];
  activeIdentifier: string | null;
  registerUnit: (identifier: string, element: HTMLElement | null) => void;
}) {
  const t = useTranslations("DocumentViewer");
  const parsedRows = rows.map((unit) => ({
    unit,
    values: new Map(
      spreadsheetFields(unit.text, t("value")).filter(
        ([label]) => !["sheet", "range"].includes(label.toLowerCase()),
      ),
    ),
  }));
  const columns = Array.from(
    parsedRows.reduce((names, row) => {
      row.values.forEach((_, name) => names.add(name));
      return names;
    }, new Set<string>()),
  );
  const summaryIdentifier = summary ? unitIdentifier(summary) : null;
  const summaryFocused = summaryIdentifier === activeIdentifier;
  const metadataUnit = summary ?? rows[0]!;

  return (
    <section
      ref={(element) => {
        if (summaryIdentifier) registerUnit(summaryIdentifier, element);
      }}
      className={cn(
        "min-w-0 scroll-mt-32 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm transition-all duration-300",
        summaryFocused && "border-indigo-300 ring-4 ring-indigo-100",
      )}
    >
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-slate-200 bg-slate-50 px-4 py-3">
        <span className="inline-flex items-center gap-2 text-sm font-semibold text-slate-800">
          <Table2 className="size-4 text-indigo-500" />
          {metadataUnit.sheet_name ?? t("spreadsheetTable")}
        </span>
        {summary?.cell_range && (
          <span className="rounded-full bg-white px-2.5 py-1 text-xs font-medium text-slate-500 shadow-sm ring-1 ring-slate-200">
            {summary.cell_range}
          </span>
        )}
      </div>
      <div className="max-w-full overflow-x-auto overscroll-x-contain">
        <table className="min-w-max border-separate border-spacing-0 text-left text-xs sm:min-w-full sm:text-sm">
          <thead>
            <tr className="bg-slate-800 text-white">
              <th scope="col" className="whitespace-nowrap border-r border-slate-700 px-3 py-2.5 font-semibold sm:px-4 sm:py-3">
                {t("cellsHeader")}
              </th>
              {columns.map((column) => (
                <th
                  key={column}
                  scope="col"
                  className="whitespace-nowrap border-r border-slate-700 px-3 py-2.5 font-semibold last:border-r-0 sm:px-4 sm:py-3"
                >
                  {column}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {parsedRows.map(({ unit, values }, rowIndex) => {
              const identifier = unitIdentifier(unit);
              const focused = identifier === activeIdentifier;
              return (
                <tr
                  key={identifier}
                  ref={(element) => registerUnit(identifier, element)}
                  className={cn(
                    "odd:bg-white even:bg-slate-50/80 hover:bg-indigo-50/60",
                    focused && "ring-2 ring-inset ring-indigo-400 [&>td]:bg-indigo-100",
                  )}
                >
                  <td className="whitespace-nowrap border-r border-t border-slate-200 px-3 py-2.5 font-mono text-[11px] font-semibold text-slate-500 sm:px-4 sm:py-3 sm:text-xs">
                    {unit.cell_range ?? rowIndex + 1}
                  </td>
                  {columns.map((column) => (
                    <td
                      key={column}
                      className="max-w-64 border-r border-t border-slate-200 px-3 py-2.5 align-top leading-5 text-slate-700 last:border-r-0 sm:max-w-md sm:px-4 sm:py-3 sm:leading-6"
                    >
                      {values.get(column) || "—"}
                    </td>
                  ))}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function UnitMetadata({ unit }: { unit: DocumentUnit }) {
  const t = useTranslations("DocumentViewer");
  const labels = [
    unit.page_number ? t("page", { number: unit.page_number }) : null,
    unit.sheet_name ? t("sheet", { name: unit.sheet_name }) : null,
    unit.cell_range ? t("cells", { range: unit.cell_range }) : null,
  ].filter(Boolean);
  if (labels.length === 0) return null;
  return (
    <div className="mb-3 flex flex-wrap gap-2">
      {labels.map((label) => (
        <span key={label} className="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-500">
          {label}
        </span>
      ))}
    </div>
  );
}

function IndexedUnit({ unit }: { unit: DocumentUnit }) {
  const t = useTranslations("DocumentViewer");
  const blockType = unit.block_type ?? "paragraph";
  if (blockType === "heading") {
    const depth = Math.min(Math.max(unit.section_path.length, 1), 3);
    return (
      <div>
        <UnitMetadata unit={unit} />
        <div
          role="heading"
          aria-level={depth + 1}
          className={cn(
            "font-semibold tracking-tight text-slate-900",
            depth === 1 ? "text-xl sm:text-2xl" : depth === 2 ? "text-lg sm:text-xl" : "text-base sm:text-lg",
          )}
        >
          {unit.text}
        </div>
      </div>
    );
  }

  if (blockType === "code") {
    return (
      <div>
        <UnitMetadata unit={unit} />
        <div className="min-w-0 overflow-hidden rounded-xl border border-slate-800 bg-slate-950">
          <div className="flex items-center gap-2 border-b border-slate-800 px-4 py-2 text-xs text-slate-400">
            <Code2 className="size-3.5" /> {t("code")}
          </div>
          <pre className="max-w-full overflow-x-auto p-3 font-mono text-xs leading-5 text-slate-100 sm:p-4 sm:text-sm sm:leading-6"><code>{unit.text}</code></pre>
        </div>
      </div>
    );
  }

  if (blockType === "table" && unit.text.includes("|")) {
    const rows = tableRows(unit.text);
    const header = rows[0] ?? [];
    const body = rows.slice(1);
    return (
      <div>
        <UnitMetadata unit={unit} />
        <div className="min-w-0 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
          <div className="flex items-center gap-2 border-b border-slate-200 bg-slate-50 px-4 py-3 text-xs font-semibold uppercase tracking-wider text-slate-500">
            <Table2 className="size-4 text-indigo-500" /> {t("indexedTable")}
          </div>
          <div className="max-w-full overflow-x-auto overscroll-x-contain">
            <table className="min-w-max border-separate border-spacing-0 text-left text-xs sm:min-w-full sm:text-sm">
              <thead>
                <tr className="bg-slate-800 text-white">
                  {header.map((cell, cellIndex) => (
                    <th
                      key={cellIndex}
                      scope="col"
                      className="whitespace-nowrap border-r border-slate-700 px-3 py-2.5 font-semibold last:border-r-0 sm:px-4 sm:py-3"
                    >
                      {cell || t("column", { number: cellIndex + 1 })}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {body.map((row, rowIndex) => (
                  <tr
                    key={rowIndex}
                    className="odd:bg-white even:bg-slate-50/80 hover:bg-indigo-50/60"
                  >
                    {header.map((_, cellIndex) => (
                      <td
                        key={cellIndex}
                        className="max-w-64 border-r border-t border-slate-200 px-3 py-2.5 align-top leading-5 text-slate-700 first:font-medium last:border-r-0 sm:max-w-md sm:px-4 sm:py-3 sm:leading-6"
                      >
                        {row[cellIndex] || "—"}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    );
  }

  if (blockType === "row") {
    return (
      <div>
        <UnitMetadata unit={unit} />
        <dl className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
          {spreadsheetFields(unit.text, t("value")).map(([label, value], index) => (
            <div
              key={`${label}-${index}`}
              className="grid border-b border-slate-100 last:border-b-0 sm:grid-cols-[minmax(9rem,0.35fr)_1fr]"
            >
              <dt className="bg-slate-50 px-4 py-3 text-xs font-semibold uppercase tracking-wide text-slate-500 sm:border-r sm:border-slate-200">
                {label}
              </dt>
              <dd className="px-4 py-3 text-sm leading-6 text-slate-700">{value || "—"}</dd>
            </div>
          ))}
        </dl>
      </div>
    );
  }

  if (blockType === "list") {
    return (
      <div>
        <UnitMetadata unit={unit} />
        <ul className="list-disc pl-6 text-[15px] leading-7 text-slate-700">
          {unit.text.split(/\n|•/).filter(Boolean).map((item, index) => <li key={index}>{item.trim()}</li>)}
        </ul>
      </div>
    );
  }

  if (blockType === "quote") {
    return (
      <div>
        <UnitMetadata unit={unit} />
        <blockquote className="border-l-4 border-indigo-200 pl-4 text-[15px] italic leading-7 text-slate-600">{unit.text}</blockquote>
      </div>
    );
  }

  return (
    <div>
      <UnitMetadata unit={unit} />
      {blockType === "page" && (
        <div className="mb-3 flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-slate-400">
          <FileText className="size-3.5" /> {t("indexedPage")}
        </div>
      )}
      {blockType === "sheet" && (
        <div className="mb-3 flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-slate-400">
          <Sheet className="size-3.5" /> {t("indexedSheet")}
        </div>
      )}
      <p className="whitespace-pre-wrap text-[15px] leading-7 text-slate-700">{unit.text}</p>
    </div>
  );
}

export default function DocumentViewerPage() {
  const t = useTranslations("DocumentViewer");
  const format = useFormatter();
  const { documentId } = useParams<{ documentId: string }>();
  const router = useRouter();
  const searchParams = useSearchParams();
  const { request } = useApiClient();
  const unitRefs = useRef(new Map<string, HTMLElement>());
  const [document, setDocument] = useState<DocumentStatusResponse | null>(null);
  const [units, setUnits] = useState<DocumentUnit[]>([]);
  const [loading, setLoading] = useState(true);
  const [unitsLoaded, setUnitsLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const requestedFocus = useMemo(() => searchParams.getAll("focus"), [searchParams]);
  const focusKey = requestedFocus.join("\u0000");
  const [citationSelection, setCitationSelection] = useState({ focusKey, index: 0 });
  const formatBytes = (bytes: number) => bytes < 1024 ? `${format.number(bytes)} B`
    : bytes < 1024 * 1024 ? `${format.number(bytes / 1024, { maximumFractionDigits: 1 })} KB`
    : `${format.number(bytes / (1024 * 1024), { maximumFractionDigits: 1 })} MB`;
  const formatDate = (iso: string) => format.dateTime(new Date(iso), { dateStyle: "medium", timeStyle: "short" });

  useEffect(() => {
    let cancelled = false;
    getDocumentStatusApi(request, documentId)
      .then((detail) => {
        if (!cancelled) setDocument(detail);
      })
      .catch((caught) => {
        if (!cancelled) setError(caught instanceof Error ? caught.message : t("fallback.load"));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [documentId, request, t]);

  useEffect(() => {
    if (!document || isTerminalDocumentStatus(document.status)) return;
    let cancelled = false;
    const poll = async () => {
      try {
        const detail = await getDocumentStatusApi(request, documentId);
        if (!cancelled) setDocument(detail);
      } catch {
        // Keep the current status visible during a transient polling failure.
      }
    };
    const timer = window.setInterval(() => void poll(), 1800);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [document, documentId, request]);

  useEffect(() => {
    if (document?.status !== "COMPLETED") return;
    let cancelled = false;
    getDocumentUnitsApi(request, documentId)
      .then((content) => {
        if (!cancelled) {
          setUnits(content);
          setError(null);
        }
      })
      .catch((caught) => {
        if (!cancelled) setError(caught instanceof Error ? caught.message : t("fallback.content"));
      })
      .finally(() => {
        if (!cancelled) setUnitsLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, [document?.status, documentId, request, t]);

  const citationUnits = useMemo(() => {
    const byIdentifier = new Map(units.map((unit) => [unitIdentifier(unit), unit]));
    return requestedFocus.map((focus) => byIdentifier.get(focus)).filter((unit): unit is DocumentUnit => Boolean(unit));
  }, [requestedFocus, units]);
  const viewerBlocks = useMemo(() => buildViewerBlocks(units), [units]);

  const selectedCitation = citationSelection.focusKey === focusKey
    ? Math.min(citationSelection.index, Math.max(citationUnits.length - 1, 0))
    : 0;

  useEffect(() => {
    const unit = citationUnits[selectedCitation];
    if (!unit) return;
    const timer = window.setTimeout(() => {
      unitRefs.current.get(unitIdentifier(unit))?.scrollIntoView({ behavior: "smooth", block: "center" });
    }, 100);
    return () => window.clearTimeout(timer);
  }, [citationUnits, selectedCitation]);

  function goBack() {
    if (window.history.length > 1) router.back();
    else router.push("/documents");
  }

  async function downloadOriginal() {
    setDownloading(true);
    try {
      const downloaded = await downloadDocumentApi(request, documentId);
      const url = URL.createObjectURL(downloaded.blob);
      const anchor = window.document.createElement("a");
      anchor.href = url;
      anchor.download = downloaded.fileName;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (caught) {
      toast.error(caught instanceof Error ? caught.message : t("fallback.download"));
    } finally {
      setDownloading(false);
    }
  }

  if (loading) {
    return <div className="grid min-h-[24rem] place-items-center"><Loader2 className="size-8 animate-spin text-indigo-600" /></div>;
  }

  if (!document || error && document === null) {
    return (
      <Card><CardContent className="py-16 text-center"><p className="font-medium text-slate-800">{t("unavailable")}</p><p className="mt-2 text-sm text-slate-500">{error}</p><Button variant="outline" className="mt-5" onClick={goBack}><ArrowLeft className="size-4" /> {t("backToDocuments")}</Button></CardContent></Card>
    );
  }

  const activeCitation = citationUnits[selectedCitation];
  const activeIdentifier = activeCitation ? unitIdentifier(activeCitation) : null;
  function registerUnit(identifier: string, element: HTMLElement | null) {
    if (element) unitRefs.current.set(identifier, element);
    else unitRefs.current.delete(identifier);
  }
  return (
    <div className="mx-auto min-w-0 max-w-5xl space-y-4 sm:space-y-5">
      <div className="grid min-w-0 gap-3 sm:grid-cols-[auto_minmax(0,1fr)_auto] sm:items-center">
        <Button variant="ghost" size="sm" className="w-fit" onClick={goBack}><ArrowLeft className="size-4" /> {t("back")}</Button>
        <div className="min-w-0">
          <h2 className="break-words text-lg font-semibold leading-tight text-slate-900 sm:truncate sm:text-xl">{document.fileName}</h2>
          <p className="mt-1 flex flex-wrap gap-x-1.5 text-xs leading-5 text-slate-500 sm:text-sm">
            <span>{document.fileType}</span><span aria-hidden="true">·</span>
            <span>{formatBytes(document.fileSizeBytes)}</span><span aria-hidden="true">·</span>
            <span>{t("uploaded", { date: formatDate(document.uploadedAt) })}</span>
          </p>
        </div>
        <div className="flex min-w-0 items-center gap-2 sm:justify-end">
          <Badge variant="outline" className="shrink-0">{document.status === "COMPLETED" ? t("status.ready") : document.status === "PROCESSING" ? t("status.indexing") : document.status === "PENDING" ? t("status.pending") : t("status.failed")}</Badge>
          <Button className="min-w-0 flex-1 sm:flex-none" variant="outline" onClick={() => void downloadOriginal()} disabled={downloading}>
            {downloading ? <Loader2 className="size-4 animate-spin" /> : <Download className="size-4" />}
            <span className="truncate">{t("downloadOriginal")}</span>
          </Button>
        </div>
      </div>

      {citationUnits.length > 0 && (
        <div className="sticky top-[4.25rem] z-10 flex items-center justify-between gap-1 rounded-xl border border-indigo-200 bg-indigo-50/95 p-1.5 shadow-sm backdrop-blur sm:gap-3 sm:px-3 sm:py-2">
          <Button variant="ghost" size="sm" className="px-2 sm:px-3" disabled={selectedCitation === 0} onClick={() => setCitationSelection({ focusKey, index: Math.max(0, selectedCitation - 1) })}><ChevronLeft className="size-4" /><span className="hidden sm:inline">{t("previous")}</span></Button>
          <p className="whitespace-nowrap text-xs font-semibold text-indigo-900 sm:text-sm">{t("citationOf", { current: selectedCitation + 1, total: citationUnits.length })}</p>
          <Button variant="ghost" size="sm" className="px-2 sm:px-3" disabled={selectedCitation === citationUnits.length - 1} onClick={() => setCitationSelection({ focusKey, index: Math.min(citationUnits.length - 1, selectedCitation + 1) })}><span className="hidden sm:inline">{t("next")}</span><ChevronRight className="size-4" /></Button>
        </div>
      )}

      {document.status === "PENDING" || document.status === "PROCESSING" ? (
        <Card><CardContent className="flex min-h-[22rem] flex-col items-center justify-center text-center"><Loader2 className="mb-4 size-8 animate-spin text-indigo-600" /><h3 className="font-semibold text-slate-900">{document.status === "PENDING" ? t("waitingToIndex") : t("indexingDocument")}</h3><p className="mt-2 max-w-md text-sm leading-6 text-slate-500">{t("viewerSoon")}</p></CardContent></Card>
      ) : document.status === "FAILED" ? (
        <Card><CardContent className="flex min-h-[22rem] flex-col items-center justify-center text-center"><FileText className="mb-4 size-9 text-red-400" /><h3 className="font-semibold text-slate-900">{t("indexingFailed")}</h3><p className="mt-2 max-w-md text-sm leading-6 text-slate-500">{document.errorMessage ?? t("indexingFailedDescription")}</p></CardContent></Card>
      ) : !unitsLoaded ? (
        <div className="grid min-h-[22rem] place-items-center"><Loader2 className="size-8 animate-spin text-indigo-600" /></div>
      ) : (
        <Card>
          <CardContent className="min-w-0 p-2 sm:p-8">
            {error && <p className="mb-5 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">{error}</p>}
            <div className="space-y-3">
              {viewerBlocks.map((block) => {
                if (block.kind === "spreadsheet-table") {
                  return (
                    <SpreadsheetTable
                      key={`spreadsheet-${block.tableId}`}
                      summary={block.summary}
                      rows={block.rows}
                      activeIdentifier={activeIdentifier}
                      registerUnit={registerUnit}
                    />
                  );
                }
                const unit = block.unit;
                const identifier = unitIdentifier(unit);
                const focused = activeIdentifier === identifier;
                return (
                  <section
                    key={identifier}
                    ref={(element) => registerUnit(identifier, element)}
                    className={cn(
                      "min-w-0 scroll-mt-32 rounded-xl border border-transparent px-2 py-3 transition-all duration-300 sm:px-4 sm:py-4",
                      focused && "border-indigo-300 bg-indigo-50 ring-4 ring-indigo-100",
                    )}
                  >
                    <IndexedUnit unit={unit} />
                  </section>
                );
              })}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
