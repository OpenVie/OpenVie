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

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { beforeEach, describe, expect, it, vi } from "vitest";
import messages from "../../../messages/en.json";
import type { BizAppManifest } from "@/types";
import { saveBizAppTemplateApi } from "@/lib/bizapp-api";
import { BizAppTemplateSaveDialog } from "./BizAppTemplateSaveDialog";

vi.mock("@/hooks/useApiClient", () => ({
  useApiClient: () => ({ request: vi.fn() }),
}));

vi.mock("@/lib/bizapp-api", () => ({
  saveBizAppTemplateApi: vi.fn(),
}));

const manifest = {
  schema: "bizapp.v1",
  kind: "purchase-approval",
  name: { vi: "Duyệt mua sắm", en: "Purchase approval" },
  version: 1,
  rbac: {},
  entities: [{ name: "requests", label: { vi: "Yêu cầu", en: "Requests" }, fields: [] }],
} as unknown as BizAppManifest;

describe("BizAppTemplateSaveDialog", () => {
  beforeEach(() => {
    vi.mocked(saveBizAppTemplateApi).mockReset();
    vi.mocked(saveBizAppTemplateApi).mockResolvedValue({} as never);
  });

  it("prefills the manifest name and publishes the manifest", async () => {
    const onOpenChange = vi.fn();
    const onSaved = vi.fn();

    render(
      <NextIntlClientProvider locale="en" messages={messages}>
        <BizAppTemplateSaveDialog
          open
          onOpenChange={onOpenChange}
          manifest={manifest}
          onSaved={onSaved}
        />
      </NextIntlClientProvider>,
    );

    expect((screen.getByLabelText("Template name") as HTMLInputElement).value)
      .toBe("Purchase approval");

    fireEvent.change(screen.getByLabelText("Summary"), {
      target: { value: "Reusable purchase flow" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save template" }));

    await waitFor(() => expect(saveBizAppTemplateApi).toHaveBeenCalledTimes(1));
    expect(saveBizAppTemplateApi).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        name: "Purchase approval",
        summary: "Reusable purchase flow",
        manifest,
      }),
    );
    expect(onSaved).toHaveBeenCalled();
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });
});
