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

package com.openvie.api.bizapp.model;

import com.openvie.api.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/**
 * A record of one manifest-defined entity inside one installed app.
 *
 * <p>Structural invariants stay in PostgreSQL (tenant scope, status); the
 * field schema is validated at the app layer against the installation's
 * frozen manifest, so community app definitions need no migration.
 */
@Getter
@Setter
@Entity
@Table(name = "bizapp_records")
public class BizAppRecord extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "app_kind", nullable = false, length = 64)
    private String appKind;

    @Column(name = "entity", nullable = false, length = 64)
    private String entity;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload;

    /** Engine runtime state (process id, last decision) — never user data. */
    @Column(name = "runtime", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> runtime = Map.of();

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
