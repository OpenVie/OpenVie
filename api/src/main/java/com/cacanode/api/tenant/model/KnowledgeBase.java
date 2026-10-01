package com.cacanode.api.tenant.model;

import com.cacanode.api.common.model.BaseEntity;
import com.cacanode.api.tenant.enums.KnowledgeBaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
        name = "knowledge_bases",
        indexes = {
                @Index(name = "idx_knowledge_base_tenant_id", columnList = "tenant_id"),
                @Index(name = "idx_knowledge_base_status", columnList = "status")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_knowledge_base_tenant_slug",
                        columnNames = {"tenant_id", "slug"}
                )
        }
)
public class KnowledgeBase extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false, length = 100)
    private String slug;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "default_locale", nullable = false, length = 20)
    private String defaultLocale = "vi-VN";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private KnowledgeBaseStatus status = KnowledgeBaseStatus.ACTIVE;

    @Column(name = "search_revision", nullable = false)
    private long searchRevision;
}
