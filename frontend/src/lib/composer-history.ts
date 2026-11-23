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

export interface ComposerHistory<T> {
  past: T[];
  present: T;
  future: T[];
}

const LIMIT = 50;

export function createHistory<T>(present: T): ComposerHistory<T> {
  return { past: [], present, future: [] };
}

export function commitHistory<T>(history: ComposerHistory<T>, next: T): ComposerHistory<T> {
  return {
    past: [...history.past, history.present].slice(-LIMIT),
    present: next,
    future: [],
  };
}

export function replaceHistory<T>(history: ComposerHistory<T>, next: T): ComposerHistory<T> {
  return { ...history, present: next };
}

/** Push a pre-drag snapshot after a layout transaction has updated `present`. */
export function commitSnapshot<T>(history: ComposerHistory<T>, snapshot: T): ComposerHistory<T> {
  return {
    past: [...history.past, snapshot].slice(-LIMIT),
    present: history.present,
    future: [],
  };
}

export function undoHistory<T>(history: ComposerHistory<T>): ComposerHistory<T> {
  const previous = history.past.at(-1);
  if (previous === undefined) return history;
  return {
    past: history.past.slice(0, -1),
    present: previous,
    future: [history.present, ...history.future],
  };
}

export function redoHistory<T>(history: ComposerHistory<T>): ComposerHistory<T> {
  const next = history.future[0];
  if (next === undefined) return history;
  return {
    past: [...history.past, history.present].slice(-LIMIT),
    present: next,
    future: history.future.slice(1),
  };
}
