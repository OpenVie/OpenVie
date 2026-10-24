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

import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

const withNextIntl = createNextIntlPlugin("./src/i18n/request.ts");
const allowedDevOrigins = process.env.NEXT_DEV_ALLOWED_ORIGINS
  ?.split(",")
  .map((host) => host.trim())
  .filter(Boolean) ?? [];

const nextConfig: NextConfig = {
  devIndicators: false, // The floating "N" dev badge confused phone QA; app UI must be the only chrome.
  allowedDevOrigins: ["127.0.0.1", ...allowedDevOrigins],
  reactCompiler: true,
  experimental: {
    proxyClientMaxBodySize: "200mb",
  },
  async redirects() {
    // Pre-hard-split URLs moved under /admin/*. Keep temporary redirects so
    // bookmarks keep working during the transition. Locales are enumerated
    // explicitly (see routing.locales) because a `:locale` wildcard would
    // also match `/admin/*` itself and loop.
    const moved = ["organization", "workspaces", "settings"];
    const locales = ["en", "vi"];
    return moved.flatMap((page) => [
      { source: `/${page}`, destination: `/admin/${page}`, permanent: false },
      ...locales.map((locale) => ({
        source: `/${locale}/${page}`,
        destination: `/${locale}/admin/${page}`,
        permanent: false,
      })),
    ]);
  },
  async rewrites() {
    return [
      {
        source: "/api/v1/:path*",
        destination: `${process.env.API_INTERNAL_URL || "http://localhost:8080"}/api/v1/:path*`,
      },
    ];
  },
};

export default withNextIntl(nextConfig);
