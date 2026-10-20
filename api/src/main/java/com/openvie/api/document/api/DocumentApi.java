package com.openvie.api.document.api;

import java.util.List;
import java.util.UUID;

public interface DocumentApi {
    void validateCitations(
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
}
