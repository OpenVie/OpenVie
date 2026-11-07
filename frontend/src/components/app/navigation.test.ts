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

import { describe, expect, it } from "vitest";
import {
  ADMIN_PATHS,
  activeNavigationHref,
  adminNavigation,
  appNavigation,
  isAdminPath,
  visibleNavigation,
} from "@/components/app/navigation";

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
      "/approvals",
      "/apps",
      "/users",
      "/admin/organization",
      "/admin/organization/api-keys",
      "/admin/workspaces",
      "/admin/apps",
      "/admin/settings",
      "/documentation",
    ]);
  });

  it("hides owner-only destinations from non-owners", () => {
    expect(visibleNavigation("MEMBER").map((item) => item.href)).toEqual([
      "/",
      "/documents",
      "/approvals",
      "/apps",
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

  it("highlights only the most specific destination", () => {
    expect(activeNavigationHref("/admin/organization/api-keys", appNavigation))
      .toBe("/admin/organization/api-keys");
    expect(activeNavigationHref("/admin/organization", appNavigation))
      .toBe("/admin/organization");
    expect(activeNavigationHref("/apps/some-app", appNavigation)).toBe("/apps");
    expect(activeNavigationHref("/documentation/api-keys", appNavigation)).toBe("/documentation");
    expect(activeNavigationHref("/", appNavigation)).toBe("/");
    expect(activeNavigationHref("/unknown", appNavigation)).toBeNull();
    expect(activeNavigationHref(undefined, appNavigation)).toBeNull();
  });

  it("keeps the admin boundary in sync with owner-only destinations", () => {
    expect(adminNavigation.map((item) => item.href).sort()).toEqual([...ADMIN_PATHS].sort());
    expect(isAdminPath("/admin/organization")).toBe(true);
    expect(isAdminPath("/admin/organization/api-keys")).toBe(true);
    expect(isAdminPath("/admin/workspaces")).toBe(true);
    expect(isAdminPath("/admin/apps")).toBe(true);
    expect(isAdminPath("/admin/settings")).toBe(true);
    expect(isAdminPath("/vi/admin/settings")).toBe(true);
    expect(isAdminPath("/organization")).toBe(false);
    expect(isAdminPath("/settings")).toBe(false);
    expect(isAdminPath("/documents")).toBe(false);
    expect(isAdminPath("/users")).toBe(false);
    expect(isAdminPath(undefined)).toBe(false);
  });
});
