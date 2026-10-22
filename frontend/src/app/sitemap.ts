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
