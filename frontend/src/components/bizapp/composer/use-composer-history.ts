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

import { useCallback, useState } from "react";
import {
  commitHistory,
  commitSnapshot as commitHistorySnapshot,
  createHistory,
  redoHistory,
  replaceHistory,
  undoHistory,
} from "@/lib/composer-history";

/**
 * Manifest history is intentionally snapshot-based: the manifest is already
 * immutable and small, and graph positions live inside `ui.layout`, so undo
 * restores nodes and edges together.
 */
export function useComposerHistory<T>(initial: T) {
  const [history, setHistory] = useState(() => createHistory(initial));
  const [revision, setRevision] = useState(0);

  const setManifest = useCallback((next: T | ((current: T) => T)) => {
    setHistory((current) => {
      const value = typeof next === "function"
        ? (next as (current: T) => T)(current.present)
        : next;
      return commitHistory(current, value);
    });
  }, []);

  const replaceManifest = useCallback((next: T) => {
    setHistory((current) => replaceHistory(current, next));
  }, []);

  const commitSnapshot = useCallback((snapshot: T) => {
    setHistory((current) => commitHistorySnapshot(current, snapshot));
  }, []);

  const undo = useCallback(() => {
    setHistory(undoHistory);
    setRevision((value) => value + 1);
  }, []);
  const redo = useCallback(() => {
    setHistory(redoHistory);
    setRevision((value) => value + 1);
  }, []);

  return {
    manifest: history.present,
    revision,
    setManifest,
    replaceManifest,
    commitSnapshot,
    undo,
    redo,
    canUndo: history.past.length > 0,
    canRedo: history.future.length > 0,
  };
}
