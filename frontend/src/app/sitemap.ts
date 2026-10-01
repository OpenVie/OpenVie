import type { MetadataRoute } from "next";
import { publicConfig } from "@/lib/public-config";

const RETAINED_PATHS = [
  "/",
  "/login",
  "/documentation",
  "/documentation/getting-started",
  "/documentation/documents",
  "/documentation/playground",
] as const;

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const base = publicConfig.siteUrl.replace(/\/$/, "");
  return RETAINED_PATHS.flatMap((path) => {
    const url = `${base}${path === "/" ? "" : path}`;
    const viUrl = `${base}/vi${path === "/" ? "" : path}`;
    return [
      { url, changeFrequency: "weekly" as const, priority: path === "/" ? 1 : 0.7 },
      { url: viUrl, changeFrequency: "weekly" as const, priority: path === "/" ? 1 : 0.7 },
    ];
  });
}
