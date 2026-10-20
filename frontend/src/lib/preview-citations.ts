/**
 * Evidence anchoring: reconnect the indexed chunks ("bằng chứng" / citation
 * units) with the **original** file preview.
 *
 * The indexer stores each unit's text plus optional hints (PDF page,
 * spreadsheet sheet/cell range, raw character offsets). A chunk is however
 * not byte-identical to the original file — whitespace is normalized and
 * table cells are flattened — so the anchor is a best-effort text match:
 * the indexer hint first, then a whitespace-collapsed search, and for
 * spreadsheet rows a letters-and-digits-only key.
 */

export type AnchorHint = {
  pageNumber?: number | null;
  sheetName?: string | null;
  cellRange?: string | null;
  sourceStart?: number | null;
  sourceEnd?: number | null;
};

export type Projection = {
  /** The normalized text the search runs on. */
  normalized: string;
  /** map[j] is the index in the original string that produced normalized[j]. */
  map: number[];
};

export type SourceRange = { start: number; end: number };

const WORD_BREAK = /\s/u;
const LOOSE_DROPPED = /[^\p{L}\p{N}]/u;

function projectText(source: string, loose: boolean): Projection {
  const output: string[] = [];
  const map: number[] = [];
  let collapsedSpace = false;
  for (let index = 0; index < source.length; index += 1) {
    const char = source[index] ?? "";
    if (char && WORD_BREAK.test(char)) {
      // Collapse whitespace runs into one space (only in collapse mode).
      if (!loose && !collapsedSpace) {
        output.push(" ");
        map.push(index);
        collapsedSpace = true;
      }
      continue;
    }
    collapsedSpace = false;
    if (loose && LOOSE_DROPPED.test(char)) continue;
    output.push(char.toLowerCase());
    map.push(index);
  }
  while (output[0] === " ") {
    output.shift();
    map.shift();
  }
  while (output[output.length - 1] === " ") {
    output.pop();
    map.pop();
  }
  return { normalized: output.join(""), map };
}

/** Lowercase text with whitespace runs collapsed to a single space. */
export function collapseText(source: string): Projection {
  return projectText(source, false);
}

/** Lowercase letters and digits only — separator/punctuation differences vanish. */
export function looseKey(source: string): string {
  return projectText(source, true).normalized;
}

function mappedRange(projection: Projection, normalizedStart: number, normalizedLength: number): SourceRange {
  return {
    start: projection.map[normalizedStart] ?? 0,
    end: (projection.map[normalizedStart + normalizedLength - 1] ?? projection.map[projection.map.length - 1] ?? 0) + 1,
  };
}

/**
 * Search `needleText` (already normalized) inside `haystack` and return its
 * span in **haystack** coordinates — no hint probing here.
 */
function searchNormalized(haystack: string, needleText: string): SourceRange | null {
  if (!needleText) return null;
  const projection = collapseText(haystack);

  // Collapse is idempotent, so a match in `projection.normalized` is valid in
  // `projection`'s normalized space (used by the DOM/PDF projections below).
  if (needleText.length <= 40) {
    const single = projection.normalized.indexOf(needleText);
    if (single >= 0) return mappedRange(projection, single, needleText.length);
  }

  const edge = Math.max(6, Math.min(28, Math.floor(needleText.length / 4)));
  const head = needleText.slice(0, edge);
  const tail = needleText.slice(-edge);
  const tailIdeal = needleText.length - tail.length; // relative to the head match
  let searchFrom = 0;
  while (searchFrom <= projection.normalized.length - head.length) {
    const headAt = projection.normalized.indexOf(head, searchFrom);
    if (headAt < 0) break;

    // Find the tail within ±8 of its ideal position, preferring the latest
    // hit so repetitive runs do not truncate the match.
    let endAt = -1;
    const windowTop = Math.min(projection.normalized.length - tail.length, headAt + tailIdeal + 8);
    for (let position = windowTop; position >= headAt + head.length; position -= 1) {
      if (projection.normalized.startsWith(tail, position)) {
        endAt = position + tail.length;
        break;
      }
    }
    if (endAt < 0) {
      const lateTail = projection.normalized.indexOf(tail, headAt + tailIdeal + 9);
      if (lateTail >= 0) endAt = lateTail + tail.length;
    }
    if (endAt < 0) {
      searchFrom = headAt + 1;
      continue;
    }

    // Verify & narrow: the candidate slice should literally contain the
    // (normalized) needle; that strips side effects of repetitive padding.
    const candidateStart = projection.map[headAt] ?? 0;
    const candidateEnd = (projection.map[endAt - 1] ?? haystack.length - 1) + 1;
    const slice = haystack.slice(candidateStart, candidateEnd);
    const sliceProjection = collapseText(slice);
    const inner = sliceProjection.normalized.indexOf(needleText);
    if (inner < 0) return { start: candidateStart, end: candidateEnd };
    const innerEnd = sliceProjection.map[inner + needleText.length - 1] ?? sliceProjection.map[sliceProjection.map.length - 1] ?? 0;
    return {
      start: candidateStart + (sliceProjection.map[inner] ?? 0),
      end: candidateStart + innerEnd + 1,
    };
  }
  return null;
}

/**
 * Locate `needle` inside the raw `haystack` and return its span in haystack
 * coordinates. The indexer hint is tried first (exact window), then
 * whitespace-collapsed search across the whole haystack.
 */
export function findSourceRange(haystack: string, needle: string, hint?: AnchorHint): SourceRange | null {
  const needleText = collapseText(needle).normalized;
  if (!needleText) return null;

  if (hint?.sourceStart != null && hint?.sourceEnd != null && hint.sourceStart >= 0
      && hint.sourceEnd > hint.sourceStart && hint.sourceEnd <= haystack.length) {
    const window = haystack.slice(hint.sourceStart, hint.sourceEnd);
    const windowProjection = collapseText(window);
    const position = windowProjection.normalized.indexOf(needleText);
    if (position >= 0) {
      return {
        start: hint.sourceStart + (windowProjection.map[position] ?? 0),
        end: hint.sourceStart + (windowProjection.map[position + needleText.length - 1] ?? -1) + 1,
      };
    }
  }

  return searchNormalized(haystack, needleText);
}

/**
 * needle → matched range in normalized coordinates.
 * | The search-only variant reused for the DOM and PDF projections
 */
export function findNormalizedRange(normalizedHaystack: string, needle: string): SourceRange | null {
  return searchNormalized(normalizedHaystack, collapseText(needle).normalized);
}

// ---------------------------------------------------------------------------
// DOM marking (DOCX pane, markdown/HTML iframes)
// ---------------------------------------------------------------------------

const IGNORED_TAGS = new Set(["script", "style"]);

export type DomProjection = {
  /** Whitespace-collapsed projection of the rendered text. */
  source: Projection;
  /** nodeIndexes[j]: the Text node that produced normalized[j]. */
  nodeIndexes: number[];
  /** localIndexes[j]: offset of normalized[j] inside that node's data. */
  localIndexes: number[];
  nodes: Text[];
};

function collectTextNodes(root: Node, owner: Document): Text[] {
  const nodes: Text[] = [];
  const walker = owner.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  let current = walker.nextNode();
  while (current) {
    const value = current.nodeValue ?? "";
    const tagName = current.parentElement?.tagName?.toLowerCase() ?? "";
    if (value.length > 0 && !tagName.startsWith("#") && !IGNORED_TAGS.has(tagName)) {
      nodes.push(current as Text);
    }
    current = walker.nextNode();
  }
  return nodes;
}

/** Build the normalized projection over the rendered preview DOM. */
export function projectTextNodes(root: Element | null, owner: Document): DomProjection | null {
  if (!root) return null;
  const nodes = collectTextNodes(root, owner);
  if (nodes.length === 0) return null;

  const normalized: string[] = [];
  const map: number[] = [];
  const nodeIndexes: number[] = [];
  const localIndexes: number[] = [];
  let collapsedSpace = false;

  for (let nodeIndex = 0; nodeIndex < nodes.length; nodeIndex += 1) {
    const value = nodes[nodeIndex]?.data ?? "";
    for (let index = 0; index < value.length; index += 1) {
      const char = value[index] ?? "";
      if (WORD_BREAK.test(char)) {
        if (!collapsedSpace) {
          normalized.push(" ");
          map.push(nodeIndex);
          nodeIndexes.push(nodeIndex);
          localIndexes.push(index);
          collapsedSpace = true;
        }
        continue;
      }
      collapsedSpace = false;
      normalized.push(char);
      map.push(nodeIndex);
      nodeIndexes.push(nodeIndex);
      localIndexes.push(index);
    }
  }

  while (normalized[0] === " ") {
    normalized.shift();
    map.shift();
    nodeIndexes.shift();
    localIndexes.shift();
  }
  while (normalized[normalized.length - 1] === " ") {
    normalized.pop();
    map.pop();
    nodeIndexes.pop();
    localIndexes.pop();
  }

  return {
    source: { normalized: normalized.join(""), map },
    nodeIndexes,
    localIndexes,
    nodes,
  };
}

/** Remove previous citation marks so a new highlight starts clean. */
export function clearPreviewMarks(root: Element): void {
  root.querySelectorAll("mark[data-preview-citation]").forEach((mark) => {
    const parent = mark.parentNode;
    if (!parent) return;
    while (mark.firstChild) parent.insertBefore(mark.firstChild, mark);
    mark.remove();
    parent.normalize();
  });
}

const MARK_CLASS = "preview-citation";

function wrapNodeSlice(owner: Document, node: Text, from: number, to: number): HTMLElement | null {
  const clippedTo = Math.max(from, Math.min(to, node.data.length));
  if (clippedTo <= from) return null;
  let target: Text = node;
  // Cut the right tail first so the left cut keeps stable offsets.
  if (clippedTo < node.data.length) {
    const right = node.splitText(clippedTo);
    void right;
  }
  if (from > 0) {
    target = node.splitText(from);
  }
  const mark = owner.createElement("mark");
  mark.dataset.previewCitation = "true";
  mark.className = MARK_CLASS;
  target.replaceWith(mark);
  mark.appendChild(target);
  return mark;
}

/**
 * Wrap the matched normalized range (`range` in normalized coordinates, as
 * returned by {@link findNormalizedRange}) in `<mark>` elements, splitting
 * boundary text nodes as needed. Returns the created marks so the caller can
 * scroll the first one into view.
 */
export function markTextRange(projection: DomProjection, range: SourceRange, owner: Document): HTMLElement[] {
  const { normalized } = projection.source;
  // normalized range pruning: whitespace-collapsed indices are contiguous.
  let first = range.start;
  let last = range.end - 1;
  if (first < 0) first = 0;
  if (last >= normalized.length) last = normalized.length - 1;
  if (first > last) return [];

    const marks: HTMLElement[] = [];
    let index = first;
    while (index <= last) {
      const nodeIndex = projection.nodeIndexes[index];
      let runEnd = index;
      while (runEnd + 1 <= last && projection.nodeIndexes[runEnd + 1] === nodeIndex) runEnd += 1;
      const node = projection.nodes[nodeIndex];
      if (typeof nodeIndex === "number" && nodeIndex >= 0 && node) {
        let from = projection.localIndexes[index];
        let to = (projection.localIndexes[runEnd] ?? 0) + 1;
        // Skip whitespace seams at node borders so the mark shows real content.
        while (from < to && /\s/u.test(node.data[from] ?? "")) from += 1;
        while (to > from && /\s/u.test(node.data[to - 1] ?? "")) to -= 1;
        const mark = wrapNodeSlice(owner, node, from, to);
        if (mark) marks.push(mark);
      }
      index = runEnd + 1;
    }
    return marks;
}

// ---------------------------------------------------------------------------
// PDF (text layer of one page)
// ---------------------------------------------------------------------------

export type PdfTextItem = {
  str: string;
  width: number;
  height: number;
  transform: number[];
  hasEOL?: boolean;
};

export type PdfProjection = {
  joined: string;
  itemIndexes: number[];
  localIndexes: number[];
};

/** Items of one page joined with " " (or "\n" at an explicit line end). */
export function projectPdfItems(items: PdfTextItem[]): PdfProjection {
  const parts: string[] = [];
  const itemIndexes: number[] = [];
  const localIndexes: number[] = [];
  for (let itemIndex = 0; itemIndex < items.length; itemIndex += 1) {
    const item = items[itemIndex];
    const str = item?.str ?? "";
    if (itemIndex > 0) {
      const separator = item?.hasEOL ? "\n" : " ";
      parts.push(separator);
      for (let separatorIndex = 0; separatorIndex < separator.length; separatorIndex += 1) {
        itemIndexes.push(itemIndex);
        localIndexes.push(str.length);
      }
    }
    parts.push(str);
    for (let index = 0; index < str.length; index += 1) {
      itemIndexes.push(itemIndex);
      localIndexes.push(index);
    }
  }
  return { joined: parts.join(""), itemIndexes, localIndexes };
}

export type PdfItemInterval = { itemIndex: number; from: number; to: number };

/** Split a matched span of the joined text into per-item character intervals. */
export function pdfItemIntervals(projection: PdfProjection, range: SourceRange): PdfItemInterval[] {
  const intervals = new Map<number, { from: number; to: number }>();
  for (let index = range.start; index < range.end; index += 1) {
    const itemIndex = projection.itemIndexes[index];
    const local = projection.localIndexes[index];
    if (itemIndex === undefined || local === undefined) continue;
    const current = intervals.get(itemIndex);
    if (!current) intervals.set(itemIndex, { from: local, to: local + 1 });
    else {
      current.from = Math.min(current.from, local);
      current.to = Math.max(current.to, local + 1);
    }
  }
  return [...intervals.entries()]
    .map(([itemIndex, bounds]) => ({ itemIndex, ...bounds }))
    .sort((a, b) => a.itemIndex - b.itemIndex);
}

export type PdfRect = { x: number; y: number; width: number; height: number };

/**
 * Proportional CSS rectangles (top-left origin, viewport scale applied) for
 * matched item intervals. Glyph boxes derive from the item transform, and the
 * horizontal split is proportional to character counts.
 */
export function pdfRectsForIntervals(
  items: PdfTextItem[],
  intervals: PdfItemInterval[],
  scale: number,
  pageHeight: number,
): PdfRect[] {
  return intervals.map(({ itemIndex, from, to }) => {
    const item = items[itemIndex];
    const transform = item?.transform ?? [1, 0, 0, 1, 0, 0];
    const a = Math.abs(transform[0] ?? 1);
    const d = Math.abs(transform[3] ?? 1);
    const e = transform[4] ?? 0;
    const f = transform[5] ?? 0;
    const glyphWidth = Math.abs(item?.width ?? 0) * a;
    const glyphHeight = Math.abs(item?.height ?? 12) * d;
    const charWidth = glyphWidth / Math.max(1, item?.str.length ?? 1);
    const x = (e + charWidth * from) * scale;
    const width = Math.max(charWidth * (to - from) * scale, 2);
    const y = (pageHeight - f - glyphHeight) * scale;
    return { x, y, width, height: Math.max(glyphHeight * scale, 6) };
  });
}

// ---------------------------------------------------------------------------
// Spreadsheet rows
// ---------------------------------------------------------------------------

export type RowRange = { first: number; last: number };

/** "B4:F9" → rows 4..9 (1-based), or null for a non-A1 hint. */
export function cellRangeRows(cellRange: string | null | undefined): RowRange | null {
  if (!cellRange) return null;
  // Sheet-name prefixes such as "Sheet2!Q3:Z8" are irrelevant to the row lines.
  const source = cellRange.split("!").pop() ?? "";
  const match = source.match(/^\$?[A-Za-z]+\$?(\d+)(?::\$?[A-Za-z]+\$?(\d+))?$/);
  if (!match) return null;
  const first = Number(match[1]);
  const last = match[2] !== undefined ? Number(match[2]) : first;
  if (!Number.isFinite(first) || !Number.isFinite(last)) return null;
  return { first: Math.min(first, last), last: Math.max(first, last) };
}

/**
 * Match evidence text against rendered table rows: the first row whose loose
 * key is contained in the evidence, then consecutive rows still contained in
 * it (gap tolerance of 2 rows for skipped summaries).
 */
export function pickMatchingRows(rows: string[][], evidenceText: string, maxRows = 32): number[] {
  const needle = looseKey(evidenceText);
  if (!needle) return [];
  const rowKeys = rows.map((row) => looseKey(row.join(" ")));
  const matches: number[] = [];
  let started = false;
  let misses = 0;
  for (let index = 0; index < rowKeys.length && matches.length < maxRows; index += 1) {
    const key = rowKeys[index] ?? "";
    if (started) {
      if (key && needle.includes(key)) {
        matches.push(index);
        misses = 0;
      } else {
        misses += 1;
        if (misses > 2) break;
      }
    } else if (key && needle.includes(key)) {
      matches.push(index);
      started = true;
    }
  }
  return matches;
}
