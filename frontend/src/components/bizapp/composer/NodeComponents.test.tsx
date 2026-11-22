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

import type { ReactNode } from "react";
import { render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { ReactFlowProvider, type NodeProps } from "@xyflow/react";
import { describe, expect, it } from "vitest";
import en from "../../../../messages/en.json";
import vi from "../../../../messages/vi.json";
import type { BizAppBlock, BizAppEntity } from "@/types";
import { NodeBlock } from "./NodeBlock";
import { NodeEntity } from "./NodeEntity";
import { ComposerValidationContext } from "./validation-context";

const BLOCK_TYPES = [
  "approval_workitem", "assistant", "knowledge_base", "report", "notify",
  "webhook_out", "external_call", "registry", "form", "timeline",
] as const;

function renderNode(
  node: ReactNode,
  {
    locale = "en",
    errors = {},
  }: { locale?: "en" | "vi"; errors?: Record<string, string> } = {},
) {
  return render(
    <NextIntlClientProvider locale={locale} messages={locale === "vi" ? vi : en}>
      <ReactFlowProvider>
        <ComposerValidationContext.Provider value={errors}>
          {node}
        </ComposerValidationContext.Provider>
      </ReactFlowProvider>
    </NextIntlClientProvider>,
  );
}

function nodeProps(id: string, data: Record<string, unknown>, selected = false): NodeProps {
  return { id, data, selected } as unknown as NodeProps;
}

const entity: BizAppEntity = {
  name: "devices",
  label: { en: "Devices", vi: "Thiết bị" },
  fields: [
    { name: "deviceId", type: "string" },
    { name: "site", type: "ref", entity: "sites" },
  ],
};

function block(type: string, params: Record<string, unknown> = {}): BizAppBlock {
  return { id: `${type}1`, type, params };
}

describe("NodeEntity", () => {
  it("renders the localized label, technical name, field count and ref target", () => {
    renderNode(<NodeEntity {...nodeProps("entity-devices", { entity })} />, { locale: "vi" });

    expect(screen.getByText("Thiết bị")).toBeInTheDocument();
    expect(screen.getByText("devices")).toBeInTheDocument();
    expect(screen.getByText("2 trường:")).toBeInTheDocument();
    expect(screen.getByText("ref → sites")).toBeInTheDocument();
  });

  it("falls back safely and shows a node-level validation error", () => {
    const { container } = renderNode(
      <NodeEntity {...nodeProps("entity-unknown", {}, true)} />,
      { errors: { "entity-unknown": "entity label.vi and label.en must be printable labels" } },
    );

    expect(screen.getAllByText("entity").length).toBeGreaterThan(0);
    expect(screen.getByTitle("entity label.vi and label.en must be printable labels"))
      .toBeInTheDocument();
    expect((container.firstChild as HTMLElement).className).toContain("ring-2");
    expect((container.firstChild as HTMLElement).className).toContain("border-destructive");
  });
});

describe("NodeBlock", () => {
  it("renders every allowed block type with its localized name", () => {
    const names = en.BizApps.composer.blockNames as Record<string, string>;
    for (const type of BLOCK_TYPES) {
      const { unmount } = renderNode(
        <NodeBlock {...nodeProps(`block-${type}1`, { block: block(type) })} />,
      );
      expect(screen.getAllByText(names[type]).length).toBeGreaterThan(0);
      unmount();
    }
  });

  it("renders localized summaries with counts", () => {
    renderNode(
      <NodeBlock
        {...nodeProps("block-report1", {
          block: block("report", { views: [{ name: "a" }, { name: "b" }] }),
        })}
      />,
      { locale: "vi" },
    );

    expect(screen.getByText("2 báo cáo")).toBeInTheDocument();
  });

  it("falls back safely and shows a node-level validation error", () => {
    const { container } = renderNode(
      <NodeBlock {...nodeProps("block-empty", {}, false)} />,
      { errors: { "block-empty": "unknown block type undefined" } },
    );

    expect(screen.getAllByText("Block").length).toBeGreaterThan(0);
    expect(screen.getByTitle("unknown block type undefined")).toBeInTheDocument();
    expect((container.firstChild as HTMLElement).className).toContain("border-destructive");
  });
});
