import { expect, test } from "@playwright/test";

const REMOVED_ROUTES = [
  "/register",
  "/check-email",
  "/verify-email",
  "/pricing",
  "/widget/preview",
  "/evidence/some-token",
  "/conversations",
  "/tickets",
  "/analytics",
  "/settings",
  "/dashboard",
  "/platform",
  "/recruitment",
  "/jobs",
  "/careers",
  "/applications",
  "/documentation/widget",
  "/documentation/api",
  "/documentation/support",
  "/documentation/recruitment",
  "/documentation/ai-interviews",
  "/documentation/webhooks",
  "/documentation/analytics-team",
  "/documentation/workspace",
];

test("logged-out visitors are sent to login instead of the chat", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveURL(/\/login(\?|$)/);
  await expect(page.locator('input[type="email"]')).toBeVisible();
});

test("logged-out visitors cannot open documents", async ({ page }) => {
  await page.goto("/documents");
  await expect(page).toHaveURL(/\/login\?next=/);
});

test("logged-out visitors cannot open users", async ({ page }) => {
  await page.goto("/users");
  await expect(page).toHaveURL(/\/login\?next=/);
});

test("removed routes have no handler", async ({ page }) => {
  for (const route of REMOVED_ROUTES) {
    const response = await page.goto(route);
    expect(response?.status(), `${route} must not resolve`).toBe(404);
  }
});

test("retained routes resolve for both locales", async ({ page }) => {
  for (const route of [
    "/login",
    "/documentation",
    "/documentation/getting-started",
    "/documentation/documents",
    "/documentation/playground",
    "/vi/login",
    "/vi/documentation",
    "/vi/documentation/playground",
  ]) {
    const response = await page.goto(route);
    expect(response?.status(), `${route} must be reachable`).toBe(200);
  }
});
