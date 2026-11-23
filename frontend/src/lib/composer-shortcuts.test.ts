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
import { composerShortcut } from "./composer-shortcuts";

const base = {
  ctrlKey: false,
  metaKey: false,
  altKey: false,
  defaultPrevented: false,
};

describe("composerShortcut", () => {
  it("maps Ctrl+S and Cmd+S to save, even inside an input", () => {
    expect(composerShortcut({ ...base, key: "s", ctrlKey: true }, {
      editable: true,
      hasSelection: false,
    })).toBe("save");
    expect(composerShortcut({ ...base, key: "S", metaKey: true }, {
      editable: true,
      hasSelection: false,
    })).toBe("save");
  });

  it("maps Delete/Backspace to delete only with a selection and outside fields", () => {
    expect(composerShortcut({ ...base, key: "Delete" }, {
      editable: false,
      hasSelection: true,
    })).toBe("delete");
    expect(composerShortcut({ ...base, key: "Backspace" }, {
      editable: false,
      hasSelection: true,
    })).toBe("delete");
    expect(composerShortcut({ ...base, key: "Delete" }, {
      editable: false,
      hasSelection: false,
    })).toBeNull();
    expect(composerShortcut({ ...base, key: "Delete" }, {
      editable: true,
      hasSelection: true,
    })).toBeNull();
  });

  it("maps Ctrl/Cmd+Z to undo, Shift+Z and Ctrl+Y to redo outside fields", () => {
    expect(composerShortcut({ ...base, key: "z", ctrlKey: true }, {
      editable: false,
      hasSelection: false,
    })).toBe("undo");
    expect(composerShortcut({ ...base, key: "z", metaKey: true, shiftKey: true }, {
      editable: false,
      hasSelection: false,
    })).toBe("redo");
    expect(composerShortcut({ ...base, key: "y", ctrlKey: true }, {
      editable: false,
      hasSelection: false,
    })).toBe("redo");
    expect(composerShortcut({ ...base, key: "z", ctrlKey: true }, {
      editable: true,
      hasSelection: false,
    })).toBeNull();
  });

  it("maps Escape to escape only with a selection and ignores modified/prevented events", () => {
    expect(composerShortcut({ ...base, key: "Escape" }, {
      editable: false,
      hasSelection: true,
    })).toBe("escape");
    expect(composerShortcut({ ...base, key: "Escape" }, {
      editable: false,
      hasSelection: false,
    })).toBeNull();
    expect(composerShortcut({ ...base, key: "s", ctrlKey: true, defaultPrevented: true }, {
      editable: false,
      hasSelection: true,
    })).toBeNull();
    expect(composerShortcut({ ...base, key: "s", ctrlKey: true, altKey: true }, {
      editable: false,
      hasSelection: true,
    })).toBeNull();
  });
});
