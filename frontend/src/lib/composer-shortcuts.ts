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

export type ComposerShortcut = "save" | "delete" | "escape" | "undo" | "redo" | null;

interface KeyboardLike {
  key: string;
  ctrlKey: boolean;
  metaKey: boolean;
  altKey: boolean;
  shiftKey?: boolean;
  defaultPrevented: boolean;
}

/**
 * Maps a keyboard event to a composer action. Save works from inputs too;
 * destructive shortcuts never fire while a field is being edited.
 */
export function composerShortcut(
  event: KeyboardLike,
  { editable, hasSelection }: { editable: boolean; hasSelection: boolean },
): ComposerShortcut {
  if (event.defaultPrevented || event.altKey) return null;

  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
    return "save";
  }

  if (editable) return null;

  if (event.ctrlKey || event.metaKey) {
    const key = event.key.toLowerCase();
    if (key === "z") return event.shiftKey ? "redo" : "undo";
    if (key === "y") return "redo";
  }

  if (event.key === "Delete" || event.key === "Backspace") {
    return hasSelection ? "delete" : null;
  }

  if (event.key === "Escape") {
    return hasSelection ? "escape" : null;
  }

  return null;
}
