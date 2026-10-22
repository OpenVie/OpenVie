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
