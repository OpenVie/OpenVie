export const publicConfig = {
  apiBaseUrl: process.env.NEXT_PUBLIC_API_BASE_URL,
  siteUrl: process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000",
} as const;
