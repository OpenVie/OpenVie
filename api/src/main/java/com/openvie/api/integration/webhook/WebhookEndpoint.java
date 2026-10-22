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

package com.openvie.api.integration.webhook;

import com.openvie.api.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One signed-event push target. Secrets are write-only: the response carries
 * the plaintext once, storage keeps only the encrypted form.
 */
@Getter
@Setter
@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "url", nullable = false, length = 1000)
    private String url;

    @Column(name = "secret_encrypted")
    private String secretEncrypted;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    /** Optional whitelist of event types; null receives everything. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "events")
    private List<String> events = new ArrayList<>();

    /** True when the endpoint accepts every event type. */
    public boolean acceptsEventType(String eventType) {
        return events == null || events.isEmpty() || events.contains(eventType);
    }
}
