import { describe, expect, it } from "vitest";

import { pagedWindow } from "@/components/ui/list-pager";

describe("pagedWindow", () => {
  const items = Array.from({ length: 25 }, (_, index) => index);

  it("slices the requested page with the default size", () => {
    const first = pagedWindow(items, 1);
    expect(first).toMatchObject({ page: 1, pageCount: 2, total: 25 });
    expect(first.slice).toEqual(items.slice(0, 20));
    const second = pagedWindow(items, 2);
    expect(second.slice).toEqual(items.slice(20));
  });

  it("honors an explicit page size and clamps overruns", () => {
    expect(pagedWindow(items, 99, 10).page).toBe(3);
    expect(pagedWindow(items, 99, 10).slice).toHaveLength(5);
    expect(pagedWindow(items, 0, 10)).toMatchObject({ page: 1, pageCount: 3 });
    expect(pagedWindow(items, -5, 10).page).toBe(1);
  });

  it("never blanks out when the list shrinks below the requested page", () => {
    const small = items.slice(0, 3);
    const shrunk = pagedWindow(small, 3, 10);
    expect(shrunk).toMatchObject({ page: 1, pageCount: 1, total: 3 });
    expect(shrunk.slice).toEqual(small);
  });

  it("reports a single page for an empty list", () => {
    expect(pagedWindow([], 4)).toMatchObject({ page: 1, pageCount: 1, total: 0, slice: [] });
  });
});
