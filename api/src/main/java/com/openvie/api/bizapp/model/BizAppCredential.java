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

import java.util.UUID;

/**
 * An organization-owned outbound secret for BizApp blocks.
 *
 * <p>{@link #encryptedData} is an AES-256-GCM envelope under
 * {@code BIZAPP_ENC_KEY}; manifests and exports reference this row by id and
 * never embed a secret value.
 */
@Getter
@Setter
@Entity
@Table(name = "bizapp_credentials")
public class BizAppCredential extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** {@code api_key} | {@code bearer} | {@code basic}. */
    @Column(name = "format", nullable = false, length = 24)
    private String format;

    @Column(name = "encrypted_data", nullable = false, columnDefinition = "text")
    private String encryptedData;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
}
