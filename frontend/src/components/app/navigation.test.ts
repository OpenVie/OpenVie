import { describe, expect, it } from "vitest";
import { appNavigation, visibleNavigation } from "@/components/app/navigation";

const REMOVED_HREFS = [
  "/dashboard",
  "/conversations",
  "/tickets",
  "/analytics",
  "/recruitment",
  "/platform",
  "/pricing",
  "/widget",
];

describe("application navigation", () => {
  it("keeps the internal-RAG destinations plus owner-only administration", () => {
    expect(appNavigation.map((item) => item.href)).toEqual([
      "/",
      "/documents",
      "/users",
      "/workspaces",
      "/settings",
      "/documentation",
    ]);
  });

  it("hides owner-only destinations from non-owners", () => {
    expect(visibleNavigation("MEMBER").map((item) => item.href)).toEqual([
      "/",
      "/documents",
      "/users",
      "/documentation",
    ]);
    expect(visibleNavigation("ORG_OWNER").map((item) => item.href)).toEqual(
      appNavigation.map((item) => item.href),
    );
  });

  it("has no entry for a removed feature surface", () => {
    const hrefs = appNavigation.map((item) => item.href);
    for (const removed of REMOVED_HREFS) {
      expect(hrefs).not.toContain(removed);
    }
  });
});
