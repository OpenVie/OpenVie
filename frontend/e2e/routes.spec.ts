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

import { expect, test } from "@playwright/test";

const REMOVED_ROUTES = [
  "/check-email",
  "/verify-email",
  "/check-login-email",
  "/verify-login",
  "/pricing",
  "/widget/preview",
  "/evidence/some-token",
  "/conversations",
  "/tickets",
  "/analytics",
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
  await expect(page).toHaveURL(/\/(login|setup)(\?|$)/);
  if ((await page.url()).includes("/login")) {
    await expect(page.locator('input[type="email"]')).toBeVisible();
  }
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
    "/setup",
    "/register",
    "/change-password",
    "/sso/callback",
    "/admin/workspaces",
    "/admin/settings",
    "/admin/organization",
    "/documentation",
    "/documentation/getting-started",
    "/documentation/documents",
    "/documentation/playground",
    "/vi/login",
    "/vi/setup",
    "/vi/sso/callback",
    "/vi/documentation",
    "/vi/documentation/playground",
  ]) {
    const response = await page.goto(route);
    expect(response?.status(), `${route} must be reachable`).toBe(200);
  }
});
