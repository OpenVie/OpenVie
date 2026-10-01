import { describe, expect, it } from "vitest";
import { appNavigation } from "@/components/app/navigation";

const REMOVED_HREFS = [
  "/dashboard",
  "/conversations",
  "/tickets",
  "/analytics",
  "/recruitment",
  "/settings",
  "/platform",
  "/pricing",
  "/widget",
];

describe("application navigation", () => {
  it("keeps only the internal-RAG destinations", () => {
    expect(appNavigation.map((item) => item.href)).toEqual([
      "/",
      "/documents",
      "/users",
      "/documentation",
    ]);
  });

  it("has no entry for a removed feature surface", () => {
    const hrefs = appNavigation.map((item) => item.href);
    for (const removed of REMOVED_HREFS) {
      expect(hrefs).not.toContain(removed);
    }
  });
});
