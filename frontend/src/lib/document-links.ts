import type { ChatCitation } from "@/types";

export function citationFocus(citation: ChatCitation): string {
  return citation.unit_id
    ? `unit:${citation.unit_id}`
    : `chunk:${citation.chunk_index}`;
}

export function documentViewerHref(
  documentId: string,
  citations: ChatCitation[] = [],
): string {
  const params = new URLSearchParams();
  const seen = new Set<string>();
  for (const citation of citations) {
    const focus = citationFocus(citation);
    if (seen.has(focus)) continue;
    seen.add(focus);
    params.append("focus", focus);
  }
  const query = params.toString();
  return `/documents/${encodeURIComponent(documentId)}${query ? `?${query}` : ""}`;
}
