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

import { fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { describe, expect, it, vi } from "vitest";
import messages from "../../../../messages/en.json";
import type { BizAppBlock, BizAppEntity } from "@/types";
import { Inspector } from "./Inspector";

function renderInspector(
  block: BizAppBlock,
  onUpdateBlock = vi.fn(),
  entities: BizAppEntity[] = [],
) {
  render(
    <NextIntlClientProvider locale="en" messages={messages}>
      <Inspector
        selectedNode={{ type: "block", data: block }}
        onUpdateEntity={vi.fn()}
        onUpdateBlock={onUpdateBlock}
        onDeleteNode={vi.fn()}
        onClose={vi.fn()}
        entities={entities}
      />
    </NextIntlClientProvider>,
  );
  return onUpdateBlock;
}

describe("Inspector approval presets", () => {
  it("shows the preset library instead of a raw BPMN path or JSON editor", () => {
    renderInspector({
      id: "approvalBlockId",
      type: "approval_workitem",
      params: {
        bpmnTemplate: "processes/bizapp-single-approval.bpmn20.xml",
        candidateRole: "WORKSPACE_ADMIN",
      },
    });

    expect(screen.getByText("Approval process")).toBeInTheDocument();
    expect(screen.getByText("Single-level approval")).toBeInTheDocument();
    expect(screen.getByText("Two-level approval")).toBeInTheDocument();
    expect(screen.queryByText(/All parameters/i)).not.toBeInTheDocument();
  });

  it("switches to the two-level preset and exposes the first-level role", () => {
    const onUpdateBlock = renderInspector({
      id: "approvalBlockId",
      type: "approval_workitem",
      params: {
        bpmnTemplate: "processes/bizapp-single-approval.bpmn20.xml",
        candidateRole: "WORKSPACE_ADMIN",
      },
    });

    fireEvent.change(screen.getAllByRole("combobox")[0], {
      target: { value: "processes/bizapp-two-level-approval.bpmn20.xml" },
    });

    expect(onUpdateBlock).toHaveBeenCalledWith(expect.objectContaining({
      params: expect.objectContaining({
        bpmnTemplate: "processes/bizapp-two-level-approval.bpmn20.xml",
        candidateRole: "WORKSPACE_ADMIN",
      }),
    }));
  });
});

describe("Inspector recipients", () => {
  it("shows recipients as labeled chips and adds a typed email", () => {
    const entities: BizAppEntity[] = [
      {
        name: "requests",
        label: { en: "Purchase requests" },
        fields: [
          { name: "manager_email", type: "string", label: { en: "Manager email" } },
        ],
      },
    ];
    const onUpdateBlock = renderInspector(
      {
        id: "notifyBlockId",
        type: "notify",
        params: { to: ["record.manager_email"], title: "New request" },
      },
      vi.fn(),
      entities,
    );

    expect(screen.getByText("Manager email")).toBeInTheDocument();
    fireEvent.change(screen.getByPlaceholderText("Choose a person or enter an email"), {
      target: { value: "approver@example.com" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    expect(onUpdateBlock).toHaveBeenCalledWith(expect.objectContaining({
      params: expect.objectContaining({
        to: ["record.manager_email", "approver@example.com"],
      }),
    }));
  });
});

describe("Inspector empty and automatic blocks", () => {
  it("offers a quick report setup instead of showing no parameters", () => {
    const onUpdateBlock = renderInspector(
      { id: "report1", type: "report", params: {} },
      vi.fn(),
      [{ name: "requests", label: { en: "Requests" }, fields: [{ name: "title", type: "string" }] }],
    );

    expect(screen.getByText("No report configured yet")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Create report" }));

    expect(onUpdateBlock).toHaveBeenCalledWith(expect.objectContaining({
      params: expect.objectContaining({
        views: [expect.objectContaining({ name: "requests_overview", entity: "requests" })],
      }),
    }));
    expect(screen.queryByText("No parameters")).not.toBeInTheDocument();
  });

  it("renders a friendly webhook event checklist", () => {
    const onUpdateBlock = renderInspector(
      { id: "webhook1", type: "webhook_out", params: { events: [] } },
    );

    expect(screen.getByText("Events to send")).toBeInTheDocument();
    expect(screen.getByText("Choose at least one event. The webhook block is not valid without one."))
      .toBeInTheDocument();
    fireEvent.click(screen.getByRole("checkbox", { name: /Record created/ }));

    expect(onUpdateBlock).toHaveBeenCalledWith(expect.objectContaining({
      params: expect.objectContaining({ events: ["record.created"] }),
    }));
  });

  it("explains that knowledge base setup is automatic", () => {
    renderInspector({ id: "knowledge1", type: "knowledge_base", params: {} });

    expect(screen.getByText("Knowledge base is automatic")).toBeInTheDocument();
    expect(screen.queryByText("No parameters")).not.toBeInTheDocument();
  });
});

describe("Report builder", () => {
  it("switches a view to a grouped summary", () => {
    const onUpdateBlock = renderInspector(
      {
        id: "report1",
        type: "report",
        params: {
          views: [{ name: "requests_overview", title: "Requests overview", entity: "requests" }],
        },
      },
      vi.fn(),
      [{
        name: "requests",
        label: { en: "Requests" },
        fields: [
          { name: "stage", type: "string", label: { en: "Stage" } },
          { name: "amount", type: "number", label: { en: "Amount" } },
        ],
      }],
    );

    fireEvent.change(screen.getByDisplayValue("List of records"), {
      target: { value: "summary" },
    });

    expect(onUpdateBlock).toHaveBeenCalledWith(expect.objectContaining({
      params: expect.objectContaining({
        views: [expect.objectContaining({ groupBy: ["stage"], metrics: [expect.objectContaining({ fn: "count" })] })],
      }),
    }));
  });
});
