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

import type { ChatCitation } from "@/types";

export function citationFocus(citation: ChatCitation): string {
  return citation.unit_id
    ? `unit:${citation.unit_id}`
    : `chunk:${citation.chunk_index}`;
}

export function documentViewerHref(
  documentId: string,
  citations: ChatCitation[] = [],
): string {
  const params = new URLSearchParams();
  const seen = new Set<string>();
  for (const citation of citations) {
    const focus = citationFocus(citation);
    if (seen.has(focus)) continue;
    seen.add(focus);
    params.append("focus", focus);
  }
  const query = params.toString();
  return `/documents/${encodeURIComponent(documentId)}${query ? `?${query}` : ""}`;
}
