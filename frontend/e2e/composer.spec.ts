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

import { expect, test, type Page } from "@playwright/test";

const USER = {
  userId: "00000000-0000-0000-0000-000000000001",
  orgId: "00000000-0000-0000-0000-000000000002",
  activeWorkspaceId: "00000000-0000-0000-0000-000000000003",
  fullName: "Composer Owner",
  email: "owner@example.test",
  orgRole: "ORG_OWNER",
  workspaceRole: "WORKSPACE_ADMIN",
  mustChangePassword: false,
};

const WORKSPACE = {
  id: USER.activeWorkspaceId,
  name: "E2E Workspace",
  slug: "e2e",
  status: "ACTIVE",
  visibility: "PRIVATE",
  isDefault: true,
  memberCount: 1,
  createdAt: "2026-01-01T00:00:00Z",
  archivedAt: null,
  archivedBy: null,
  legalHold: false,
  purgeEligibleAt: null,
};

async function mockComposerApi(page: Page) {
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname.endsWith("/auth/refresh")) {
      await route.fulfill({
        json: { accessToken: "e2e-token", tokenType: "Bearer", expiresIn: 3600, user: USER },
      });
      return;
    }
    if (url.pathname.endsWith("/admin/workspaces")) {
      await route.fulfill({ json: [WORKSPACE] });
      return;
    }
    if (url.pathname.endsWith("/bizapps/validate")) {
      await route.fulfill({ json: { ok: true, errors: [], issues: [] } });
      return;
    }
    await route.fulfill({ status: 404, json: { message: `unmocked ${request.method()} ${url.pathname}` } });
  });
}

test.beforeEach(async ({ page }) => {
  await mockComposerApi(page);
});

test("composer adds, undoes and redoes an entity with the keyboard", async ({ page }) => {
  await page.goto("/en/admin/apps/compose");
  await expect(page.getByRole("button", { name: /add entity/i })).toBeVisible();

  await page.getByRole("button", { name: /add entity/i }).click();
  await expect(page.getByText("Entity Properties")).toBeVisible();

  await page.keyboard.press("Control+z");
  await expect(page.getByText("Select an entity or block to configure properties.")).toBeVisible();
  await expect(page.getByText("entity2", { exact: true })).toHaveCount(0);

  await page.keyboard.press("Control+Shift+z");
  await expect(page.getByText("entity2", { exact: true })).toHaveCount(1);
});

test("composer creates a report view and renders its builder", async ({ page }) => {
  await page.goto("/en/admin/apps/compose");
  await page.getByRole("button", { name: "Report", exact: true }).click();

  await expect(page.getByText("Report title")).toBeVisible();
  await expect(page.getByText("Data source")).toBeVisible();
});

test("assistant can select a knowledge base from the inspector", async ({ page }) => {
  await page.goto("/en/admin/apps/compose");
  await page.getByRole("button", { name: "Knowledge base", exact: true }).click();
  await page.getByRole("button", { name: "Assistant", exact: true }).click();

  if ((page.viewportSize()?.width ?? 0) >= 1024) {
    await page.getByRole("button", { name: /list view/i }).click();
  } else {
    await page.getByRole("button", { name: /visual canvas/i }).click();
    await page.getByRole("menuitem", { name: /list view/i }).click();
  }
  await page.getByRole("button", { name: /^Assistant assistant2/ }).click();

  const picker = page.getByLabel("Knowledge base");
  await expect(picker).toBeVisible();
  await picker.selectOption("knowledge_base1");
  await expect(picker).toHaveValue("knowledge_base1");
});
