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
 * An organization-crafted BizApp template in the marketplace.
 *
 * <p>Bundled templates live as JSON files on the classpath; this table stores
 * only the organization's own reusable copies. {@link #manifest} is the raw
 * JSON accepted by {@code BizAppManifestValidator} — the validator, not DDL,
 * is the schema authority.
 */
@Getter
@Setter
@Entity
@Table(name = "bizapp_marketplace_templates")
public class BizAppMarketplaceTemplate extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** Stable app key the template installs as, e.g. {@code crm}. */
    @Column(name = "kind", nullable = false, length = 64)
    private String kind;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "manifest", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> manifest;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
}
