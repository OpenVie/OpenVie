import { describe, expect, it } from "vitest"

import {
  cellRangeRows,
  clearPreviewMarks,
  collapseText,
  findNormalizedRange,
  findSourceRange,
  looseKey,
  markTextRange,
  pdfItemIntervals,
  pdfRectsForIntervals,
  pickMatchingRows,
  projectPdfItems,
  projectTextNodes,
} from "@/lib/preview-citations"

describe("collapseText", () => {
  it("collapses whitespace runs and maps normalized indices back to the source", () => {
    const haystack = "one   two\tthree"
    const projection = collapseText(haystack)
    expect(projection.normalized).toBe("one two three")
    // Runs keep their first whitespace char; "two" resumes at its own index.
    expect(projection.map).toEqual([0, 1, 2, 3, 6, 7, 8, 9, 10, 11, 12, 13, 14])
    expect(haystack[projection.map[4]]).toBe("t")
  })

  it("is idempotent, so projection output can be re-projected", () => {
    const once = collapseText("a  b   c").normalized
    expect(collapseText(once).normalized).toBe(once)
  })
})

describe("looseKey", () => {
  it("keeps letters and digits only, matching flattened table text", () => {
    expect(looseKey("A | B (Q3)")).toBe("abq3")
    expect(looseKey("A, B; Q-3")).toBe("abq3")
    expect(looseKey("Tiếng Việt 12")).toBe("tiếngviệt12")
  })
})

describe("findSourceRange", () => {
  it("matches across whitespace differences and reports haystack coordinates", () => {
    const haystack = "one   two three"
    expect(findSourceRange(haystack, "One Two")).toEqual({ start: 0, end: 9 })
    expect(haystack.slice(0, 9)).toBe("one   two")
  })

  it("returns null when the needle is absent", () => {
    expect(findSourceRange("alpha beta", "gamma")).toBeNull()
  })

  it("prefers the indexer hint window when it contains the needle", () => {
    const haystack = "ignore prefix. Actual content here; trailer."
    const result = findSourceRange(haystack, "actual content", { sourceStart: 15, sourceEnd: 34 })
    expect(result).toEqual({ start: 15, end: 29 })
    expect(haystack.slice(result?.start, result?.end)).toBe("Actual content")
  })

  it("ignores nonsense hint windows and falls back to search", () => {
    const haystack = "needle somewhere in here"
    expect(findSourceRange(haystack, "somewhere", { sourceStart: 400, sourceEnd: 500 })).toEqual({ start: 7, end: 16 })
  })

  it("anchors long needles with head/tail snippets", () => {
    const haystack = ">" + "x".repeat(60) + "target" + "y".repeat(60) + "<"
    const needle = "target" + "y".repeat(60)
    const result = findSourceRange(haystack, needle)
    expect(result).not.toBeNull()
    expect(haystack.slice(result?.start, result?.end)).toBe(needle)
  })
})

describe("findNormalizedRange", () => {
  it("searches inside a projection's normalized text", () => {
    expect(findNormalizedRange(collapseText("alpha beta gamma").normalized, "BETA GAM")).toEqual({ start: 6, end: 14 })
  })
})

describe("projectTextNodes + markTextRange", () => {
  it("marks a match that spans elements, then unsplits cleanly", () => {
    const root = document.createElement("section")
    root.innerHTML = "<p>Hello <b>brave</b> world</p>"
    const projection = projectTextNodes(root, document)
    expect(projection).not.toBeNull()
    const range = findNormalizedRange(projection?.source.normalized ?? "", "brave world")
    const marks = markTextRange(projection!, range!, document)
    expect(marks.length).toBe(2)
    expect(root.querySelectorAll("mark[data-preview-citation]").length).toBe(2)
    expect(root.querySelectorAll("mark[data-preview-citation]")[0]?.textContent).toBe("brave")
    expect(root.querySelectorAll("mark[data-preview-citation]")[1]?.textContent).toBe("world")
    expect(root.textContent).toBe("Hello brave world")

    clearPreviewMarks(root)
    expect(root.querySelectorAll("mark[data-preview-citation]").length).toBe(0)
    expect(root.textContent).toBe("Hello brave world")
  })

  it("marks partial text inside one node", () => {
    const root = document.createElement("div")
    root.textContent = "alpha beta gamma"
    const projection = projectTextNodes(root, document)
    const range = findNormalizedRange(projection?.source.normalized ?? "", "beta")
    const marks = markTextRange(projection!, range!, document)
    expect(marks.length).toBe(1)
    expect(marks[0]?.textContent).toBe("beta")
    expect(root.textContent).toBe("alpha beta gamma")
  })
})

describe("projectPdfItems", () => {
  it("joins items with spaces and line breaks, mapping chars back to items", () => {
    const projection = projectPdfItems([
      { str: "A", width: 10, height: 10, transform: [1, 0, 0, 1, 0, 0] },
      { str: "B", width: 10, height: 10, transform: [1, 0, 0, 1, 5, 5], hasEOL: true },
    ])
    expect(projection.joined).toBe("A\nB")
    // The separator anchors to the start of the following item.
    expect(projection.itemIndexes).toEqual([0, 1, 1])
    expect(projection.localIndexes).toEqual([0, 1, 0])
  })

  const pdfItems = [
    { str: "A", width: 10, height: 10, transform: [1, 0, 0, 1, 0, 0] },
    { str: "B", width: 10, height: 10, transform: [1, 0, 0, 1, 5, 5], hasEOL: true },
  ]
  const pdfProjection = projectPdfItems(pdfItems)

  it("splits a full text match into per-item intervals", () => {
    const range = findNormalizedRange(pdfProjection.joined, "a b")
    const intervals = pdfItemIntervals(pdfProjection, range!)
    expect(intervals).toEqual([
      { itemIndex: 0, from: 0, to: 1 },
      { itemIndex: 1, from: 0, to: 2 },
    ])
  })
})

describe("pdfRectsForIntervals", () => {
  it("converts item transform boxes into proportional CSS rectangles", () => {
    const rects = pdfRectsForIntervals(
      [{ str: "abc", width: 30, height: 12, transform: [1, 0, 0, 1, 10, 20] }],
      [{ itemIndex: 0, from: 0, to: 2 }],
      2,
      300,
    )
    expect(rects.length).toBe(1)
    const rect = rects[0]!
    expect(rect.x).toBeCloseTo(20) // e = 10 · scale 2
    expect(rect.width).toBeCloseTo(40) // 2 of 3 chars of a 30-unit wide item at scale 2
    expect(rect.y).toBeCloseTo((300 - 20 - 12) * 2)
    expect(rect.height).toBeCloseTo(24)
  })
})

describe("cellRangeRows", () => {
  it("parses A1 ranges to 1-based rows", () => {
    expect(cellRangeRows("B4:F9")).toEqual({ first: 4, last: 9 })
    expect(cellRangeRows("$D$7:$D$7")).toEqual({ first: 7, last: 7 })
    expect(cellRangeRows("C12")).toEqual({ first: 12, last: 12 })
    expect(cellRangeRows("Sheet2!Q3:Z8")).toEqual({ first: 3, last: 8 })
    expect(cellRangeRows("garbage")).toBeNull()
    expect(cellRangeRows(null)).toBeNull()
  })
})

describe("pickMatchingRows", () => {
  it("finds the first contained row and consecutive still-contained rows", () => {
    const rows = [["A", "B"], ["E", "F"], ["C", "D"], ["F", "F"]]
    // The evidence chunk flattens rows: "A | B \nC | D".
    expect(pickMatchingRows(rows, "A | B \nC | D")).toEqual([0, 2])
  })

  it("returns an empty list for empty input", () => {
    expect(pickMatchingRows([[], []], "A | B")).toEqual([])
  })
})
