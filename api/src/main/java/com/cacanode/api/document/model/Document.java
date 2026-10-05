package com.cacanode.api.document.model;

import com.cacanode.api.common.model.BaseEntity;
import com.cacanode.api.document.enums.DocumentStatus;
import com.cacanode.api.document.enums.DocumentType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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
                @Index(name = "idx_documents_tenant_kb_content_hash", columnList = "tenant_id,knowledge_base_id,content_hash")
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
}
