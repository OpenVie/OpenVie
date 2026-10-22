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

package com.openvie.api.document.api;

/**
 * Approval state of a document, written back by the workflow module when an
 * approval process opens and when it settles.
 *
 * <p>It lives in the module's public contract because the workflow module owns
 * the process that produces it; this is deliberately separate from the
 * ingestion state ({@code DocumentStatus}), which is an independent axis — a
 * document can be fully indexed and still be waiting for review.
 *
 * <p>{@code null} on the document row means "never submitted", which the API
 * surfaces as an absent value rather than a fourth constant.
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}