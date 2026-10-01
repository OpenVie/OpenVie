package com.cacanode.api.tenant.model;

import com.cacanode.api.common.model.BaseEntity;
import com.cacanode.api.tenant.api.TenantStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name="tenants")
public class Tenant extends BaseEntity {

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", unique = true, nullable = false, length = 100)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private TenantStatus status = TenantStatus.PENDING;

    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    @Column(name = "suspended_reason")
    private String suspendedReason;

    // Usage quota
    @Column(name = "max_documents")
    private Integer maxDocuments = 50;

    @Column(name = "max_messages")
    private Integer maxMessages = 10_000;

    @Column(name = "max_storage_mb")
    private Integer maxStorageMb = 10_240;

    @Column(name = "max_team_members")
    private Integer maxTeamMembers = 5;

}
