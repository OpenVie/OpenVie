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
 * One BizApp installed into one workspace.
 *
 * <p>{@link #manifest} freezes the exact JSON validated at install time: later
 * template edits never mutate a running app; upgrading replaces it through a
 * new install call. {@link #processKey} pins the Flowable process definition
 * deployed for the app's {@code approval_workitem} block.
 */
@Getter
@Setter
@Entity
@Table(name = "bizapp_installations")
public class BizAppInstallation extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "kind", nullable = false, length = 64)
    private String kind;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "manifest", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> manifest;

    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> config = Map.of();

    @Column(name = "process_key", length = 128)
    private String processKey;

    @Column(name = "installed_by", nullable = false)
    private UUID installedBy;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
