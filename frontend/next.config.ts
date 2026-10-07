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
