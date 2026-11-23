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
  commitHistory,
  commitSnapshot,
  createHistory,
  redoHistory,
  replaceHistory,
  undoHistory,
} from "./composer-history";

describe("composer history", () => {
  it("undoes and redoes manifest edits in order", () => {
    let history = createHistory("a");
    history = commitHistory(history, "b");
    history = commitHistory(history, "c");

    expect(history.present).toBe("c");
    history = undoHistory(history);
    expect(history.present).toBe("b");
    history = undoHistory(history);
    expect(history.present).toBe("a");
    history = redoHistory(history);
    expect(history.present).toBe("b");
    history = redoHistory(history);
    expect(history.present).toBe("c");
  });

  it("clears redo when a new edit lands", () => {
    let history = createHistory("a");
    history = commitHistory(history, "b");
    history = undoHistory(history);
    expect(history.future).toEqual(["b"]);
    history = commitHistory(history, "c");
    expect(history.future).toEqual([]);
    expect(history.present).toBe("c");
  });

  it("replaces transient layout state without adding an undo step", () => {
    let history = createHistory("a");
    history = commitHistory(history, "b");
    history = replaceHistory(history, "b-drag");
    expect(history.past).toEqual(["a"]);
    expect(history.present).toBe("b-drag");

    history = commitSnapshot(history, "b");
    expect(history.present).toBe("b-drag");
    expect(history.past).toEqual(["a", "b"]);
  });

  it("does nothing at the ends of the history", () => {
    const history = createHistory("a");
    expect(undoHistory(history)).toEqual(history);
    expect(redoHistory(history)).toEqual(history);
  });
});
