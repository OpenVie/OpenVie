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

import java.util.List;
import java.util.UUID;

public interface DocumentApi {
    void validateCitations(
            UUID tenantId, UUID knowledgeBaseId, List<UUID> documentIds);

    /**
     * Authoritative chat-scope check: every requested document must be a
     * COMPLETED document of the same tenant and knowledge base.
     *
     * @throws com.openvie.api.common.exception.custom.BadRequestException
     *             when any requested document is missing or not indexed yet
     */
    void validateChatScope(
            UUID tenantId, UUID knowledgeBaseId, List<UUID> documentIds);

    /**
     * Resolves the workspace-bounded document facts an approval process needs
     * at start time (issue #8): existence inside the workspace plus the file
     * name the sensitivity scorer runs on.
     *
     * @throws com.openvie.api.common.exception.custom.ResourceNotFoundException
     *             when the document does not exist in the workspace
     */
    ApprovalDocumentSnapshot requireApprovalCandidate(UUID tenantId, UUID documentId);

    /**
     * Server-resolved document facts only: never caller-supplied text, so the
     * submitter cannot reshape what the sensitivity policy scores.
     */
    record ApprovalDocumentSnapshot(
            UUID documentId,
            UUID tenantId,
            UUID knowledgeBaseId,
            String fileName) {
    }

    /**
     * Records that an approval process is now open for this document, with the
     * publish target chosen by the submitter. Idempotent while the document is
     * already PENDING; a settled document may be re-submitted (the latest
     * process owns the state).
     *
     * @throws com.openvie.api.common.exception.custom.ResourceNotFoundException
     *             when the document does not exist in the workspace
     */
    void markApprovalPending(UUID tenantId, UUID documentId, String publishTarget);

    /**
     * Writes the terminal decision of an approval process onto the document
     * row. The Flowable process remains the journal of who decided what and
     * when; this makes the document's own state authoritative for listings,
     * caches, and downstream consumers.
     *
     * @throws com.openvie.api.common.exception.custom.ResourceNotFoundException
     *             when the document does not exist in the workspace
     */
    void recordApprovalDecision(UUID tenantId, UUID documentId, ApprovalStatus decision, UUID decidedBy);
}
