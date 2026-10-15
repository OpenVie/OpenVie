import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

const withNextIntl = createNextIntlPlugin("./src/i18n/request.ts");
const allowedDevOrigins = process.env.NEXT_DEV_ALLOWED_ORIGINS
  ?.split(",")
  .map((host) => host.trim())
  .filter(Boolean) ?? [];

const nextConfig: NextConfig = {
  allowedDevOrigins: ["127.0.0.1", ...allowedDevOrigins],
  reactCompiler: true,
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
