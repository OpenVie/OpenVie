import { describe, expect, it } from "vitest"

import {
  detectCsvDelimiter,
  escapeHtml,
  markdownToHtml,
  parseCsv,
  resolvePreviewKind,
  textDocumentHtml,
} from "@/lib/preview-renderers"

describe("resolvePreviewKind", () => {
  it("prefers the backend DocumentType over the file extension", () => {
    expect(resolvePreviewKind("report.dontcare", "PDF")).toBe("pdf")
    expect(resolvePreviewKind("report.md", "MARKDOWN")).toBe("markdown")
    expect(resolvePreviewKind("report.txt", "TXT")).toBe("text")
    expect(resolvePreviewKind("report.html", "HTML")).toBe("html")
    expect(resolvePreviewKind("report.docx", "DOCX")).toBe("docx")
    expect(resolvePreviewKind("report.xlsx", "XLSX")).toBe("xlsx")
    expect(resolvePreviewKind("report.csv", "CSV")).toBe("csv")
  })

  it("falls back to the file extension for approval views without a fileType", () => {
    expect(resolvePreviewKind("bao-cao-quy-3.pdf")).toBe("pdf")
    expect(resolvePreviewKind("plan.2024.DOCX")).toBe("docx")
    expect(resolvePreviewKind("notes.md")).toBe("markdown")
    expect(resolvePreviewKind("data.XLS")).toBe("xlsx")
    expect(resolvePreviewKind("page.htm")).toBe("html")
    expect(resolvePreviewKind("raw.log")).toBe("unsupported")
  })

  it("keeps lowercase fileType values working", () => {
    expect(resolvePreviewKind("file.bin", "pdf")).toBe("pdf")
  })
})

describe("parseCsv", () => {
  it("splits simple rows and drops empty lines", () => {
    expect(parseCsv("a,b\n1,2\n\n", ",")).toEqual([["a", "b"], ["1", "2"]])
  })

  it("honors quoted fields containing delimiters and escaped quotes", () => {
    expect(parseCsv('name,note\n"He, Nguyen","said ""hi"""', ",")).toEqual([
      ["name", "note"],
      ["He, Nguyen", 'said "hi"'],
    ])
  })

  it("handles CRLF line endings", () => {
    expect(parseCsv("a;b\r\n1;2\r\n", ";")).toEqual([["a", "b"], ["1", "2"]])
  })

  it("keeps empty trailing fields and rows with a single empty cell out", () => {
    expect(parseCsv("a,b,c\n1,,", ",")).toEqual([["a", "b", "c"], ["1", "", ""]])
  })
})

describe("detectCsvDelimiter", () => {
  it("prefers the delimiter with the most separators outside quotes", () => {
    expect(detectCsvDelimiter("name;age;city\n")).toBe(";")
    expect(detectCsvDelimiter("name,age,city\n")).toBe(",")
    expect(detectCsvDelimiter("name\tage\tcity\n")).toBe("\t")
  })

  it("ignores separators inside quoted header cells", () => {
    expect(detectCsvDelimiter('"surname, street",age\n')).toBe(",")
  })

  it("defaults to comma for ambiguous data", () => {
    expect(detectCsvDelimiter("single-column\n")).toBe(",")
  })
})

describe("escapeHtml", () => {
  it("escapes markup and quotes", () => {
    expect(escapeHtml('<script>alert("x")</script>')).toBe("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;")
    expect(escapeHtml("Tom & Jerry")).toBe("Tom &amp; Jerry")
  })
})

describe("markdownToHtml", () => {
  it("escapes raw inline HTML so it stays inert text", () => {
    const html = markdownToHtml("<script>alert(1)</script>")
    expect(html).toContain("&lt;script&gt;")
    expect(html).not.toContain("<script>")
  })

  it("renders headings, lists, quotes and inline styles", () => {
    const html = markdownToHtml(
      "## **Bold** title\n\n- first\n- second\n\n1. one\n2. two\n\n> quoted\n\nSome `code` here.\n",
    )
    expect(html).toContain('<h2><strong>Bold</strong> title</h2>')
    expect(html).toContain("<ul><li>first</li><li>second</li></ul>")
    expect(html).toContain("<ol><li>one</li><li>two</li></ol>")
    expect(html).toContain("<blockquote>quoted</blockquote>")
    expect(html).toContain("<code>code</code>")
  })

  it("renders pipe tables with headers and escapes cell content", () => {
    const html = markdownToHtml(
      "| A | B |\n| --- | --- |\n| 1 | `<i>` |\n",
    )
    expect(html).toContain("<thead><tr><th>A</th><th>B</th></tr></thead>")
    expect(html).not.toContain("<i>")
    expect(html).toContain("<code>&lt;i&gt;</code>")
  })

  it("renders fenced code blocks verbatim", () => {
    const html = markdownToHtml("```\nif (x < 1) {}\n```")
    expect(html).toContain("if (x &lt; 1) {}")
  })

  it("only turns http(s) and mailto links into anchors", () => {
    const html = markdownToHtml("[ok](https://example.com) [bad](javascript:alert(1))")
    expect(html).toContain('<a href="https://example.com"')
    expect(html).not.toContain('href="javascript:')
    expect(html).toContain("[bad](javascript:alert(1))")
  })
})

describe("textDocumentHtml", () => {
  it("wraps rendered content with the shared styles and a title", () => {
    const document = textDocumentHtml('<p>hello</p>', "test.md")
    expect(document).toContain('<title>test.md</title>')
    expect(document).toContain("<style>")
    expect(document).toContain("<p>hello</p>")
    expect(document).not.toContain("<script>")
  })
})
