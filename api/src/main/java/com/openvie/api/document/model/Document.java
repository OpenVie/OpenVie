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

package com.openvie.api.document.model;

import com.openvie.api.common.model.BaseEntity;
import com.openvie.api.document.api.ApprovalStatus;
import com.openvie.api.document.enums.DocumentStatus;
import com.openvie.api.document.enums.DocumentType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;


@Getter
@Setter
@Entity
@Table(
        name = "documents",
        indexes = {
                @Index(name = "idx_document_tenant_id", columnList = "tenant_id"),
                @Index(name = "idx_document_status", columnList = "status"),
                @Index(name = "idx_document_job_id", columnList = "job_id"),
                @Index(name = "idx_document_knowledge_base_id", columnList = "knowledge_base_id"),
                @Index(name = "idx_document_tenant_status", columnList = "tenant_id,status"),
                @Index(name = "idx_document_tenant_knowledge_base", columnList = "tenant_id,knowledge_base_id"),
                @Index(name = "idx_documents_tenant_kb_filename", columnList = "tenant_id,knowledge_base_id,file_name"),
                @Index(name = "idx_documents_tenant_kb_content_hash", columnList = "tenant_id,knowledge_base_id,content_hash"),
                @Index(name = "idx_documents_tenant_approval_status", columnList = "tenant_id,approval_status")
        }
)
public class Document extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 50)
    private DocumentType fileType;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private DocumentStatus status = DocumentStatus.PENDING;

    @Column(name = "job_id")
    private String jobId;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /**
     * How the text was obtained: text (native layer), ocr (recognized page
     * images), or mixed (both). Null for legacy rows and non-PDF sources.
     */
    @Column(name = "text_source", length = 16)
    private String textSource;

    /** Number of source pages when known (PDF and images); null otherwise. */
    @Column(name = "page_count")
    private Integer pageCount;

    /**
     * Approval state written back by the workflow module. {@code null} means
     * the document was never submitted for approval.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", length = 20)
    private ApprovalStatus approvalStatus;

    /** Publish target chosen at submission: workspace, shared, or public. */
    @Column(name = "approval_target", length = 20)
    private String approvalTarget;

    @Column(name = "approval_decided_by")
    private UUID approvalDecidedBy;

    @Column(name = "approval_decided_at")
    private LocalDateTime approvalDecidedAt;
}
