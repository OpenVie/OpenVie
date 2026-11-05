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

package com.openvie.api.bizapp.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface BizAppRuntimeApi {
    List<InstalledAppSummary> listInstalledSummary(UUID tenantId);

    List<MarketplaceTemplateSummary> listMarketplaceSummary(UUID tenantId);

    AppActionOffer offerInstall(UUID tenantId, UUID orgId, String appKind, UUID actorId);

    AppActionOffer offerUninstall(UUID tenantId, String appKind, UUID actorId);

    InstallOutcome executeOffer(UUID tenantId, UUID orgId, UUID actorId, AppActionOffer offer);

    DraftOutcome createDraftFromSession(UUID tenantId, UUID orgId, UUID actorId, UUID sessionId, String hint);

    List<UUID> listProjectedDocumentIds(UUID tenantId, String appKind);

    List<UUID> listProjectedDocumentIds(UUID tenantId, String appKind, String entity);

    record FieldSummary(
            String name,
            String type,
            boolean required,
            List<String> options,
            Integer maxLen,
            String format,
            String label
    ) {}

    record EntitySchemaSummary(
            String name,
            String label,
            List<FieldSummary> fields
    ) {}

    record AppSchemaSummary(
            String kind,
            String name,
            int version,
            List<EntitySchemaSummary> entities
    ) {}

    List<AppSchemaSummary> listAppSchemas(UUID tenantId);

    RecordCommandOutcome executeRecordCommand(UUID tenantId, UUID orgId, UUID actorId, String callerRole, RecordCommand command);
    record RecordCommand(
            String kind,
            String appKind,
            String entity,
            UUID recordId,
            Map<String, Object> payload,
            String payloadHash
    ) {}

    record RecordCommandOutcome(
            boolean success,
            UUID recordId,
            String message
    ) {}
    record FieldDetail(
            String name,
            String type,
            boolean required,
            String label,
            List<String> options,
            String format,
            Map<String, String> optionDisplayLabels,
            Map<String, List<String>> optionAliases
    ) {
        public FieldDetail(String name, String type, boolean required, String label) {
            this(name, type, required, label, List.of(), null, Map.of(), Map.of());
        }

        public FieldDetail(String name, String type, boolean required, String label, List<String> options) {
            this(name, type, required, label, options, null, Map.of(), Map.of());
        }

        public FieldDetail(String name, String type, boolean required, String label, List<String> options, String format) {
            this(name, type, required, label, options, format, Map.of(), Map.of());
        }
    }

    record EntityDetail(
            String name,
            String label,
            List<FieldDetail> fields
    ) {
        public EntityDetail {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }

        public String fieldLabel(String fieldName) {
            if (fields == null || fieldName == null) return fieldName;
            for (FieldDetail f : fields) {
                if (fieldName.equalsIgnoreCase(f.name())) {
                    return f.label() != null && !f.label().isBlank() ? f.label() : f.name();
                }
            }
            return fieldName;
        }

        public FieldDetail findField(String fieldName) {
            if (fields == null || fieldName == null) return null;
            for (FieldDetail f : fields) {
                if (fieldName.equalsIgnoreCase(f.name())) {
                    return f;
                }
            }
            return null;
        }
    }

    record InstalledAppSummary(
            UUID id,
            String kind,
            String name,
            int version,
            String description,
            List<String> entities,
            List<String> views,
            String status,
            List<EntityDetail> entityDetails,
            List<String> triggers
    ) {
        public InstalledAppSummary {
            entities = entities == null ? List.of() : List.copyOf(entities);
            views = views == null ? List.of() : List.copyOf(views);
            entityDetails = entityDetails == null ? List.of() : List.copyOf(entityDetails);
            triggers = triggers == null ? List.of() : List.copyOf(triggers);
        }

        public InstalledAppSummary(
                UUID id,
                String kind,
                String name,
                int version,
                String description,
                List<String> entities,
                List<String> views,
                String status,
                List<EntityDetail> entityDetails
        ) {
            this(id, kind, name, version, description, entities, views, status, entityDetails, List.of());
        }

        public InstalledAppSummary(
                UUID id,
                String kind,
                String name,
                int version,
                String description,
                List<String> entities,
                List<String> views,
                String status
        ) {
            this(id, kind, name, version, description, entities, views, status, List.of(), List.of());
        }
    }

    record MarketplaceTemplateSummary(
            String kind,
            String name,
            int version,
            String description,
            List<String> entities,
            List<String> views,
            String templateRef,
            List<EntityDetail> entityDetails,
            List<String> triggers
    ) {
        public MarketplaceTemplateSummary {
            entities = entities == null ? List.of() : List.copyOf(entities);
            views = views == null ? List.of() : List.copyOf(views);
            entityDetails = entityDetails == null ? List.of() : List.copyOf(entityDetails);
            triggers = triggers == null ? List.of() : List.copyOf(triggers);
        }

        public MarketplaceTemplateSummary(
                String kind,
                String name,
                int version,
                String description,
                List<String> entities,
                List<String> views,
                String templateRef,
                List<EntityDetail> entityDetails
        ) {
            this(kind, name, version, description, entities, views, templateRef, entityDetails, List.of());
        }

        public MarketplaceTemplateSummary(
                String kind,
                String name,
                int version,
                String description,
                List<String> entities,
                List<String> views,
                String templateRef
        ) {
            this(kind, name, version, description, entities, views, templateRef, List.of(), List.of());
        }
    }

    record AppActionOffer(
            UUID actionId,
            String kind,
            String appKind,
            String appName,
            int version,
            String description,
            String requiresRole,
            String templateRef
    ) {}

    record InstallOutcome(
            boolean success,
            UUID installationId,
            String message
    ) {}

    record DraftOutcome(
            boolean success,
            UUID draftId,
            String title,
            List<String> entities,
            List<String> errors
    ) {
        public DraftOutcome {
            entities = entities == null ? List.of() : List.copyOf(entities);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    record RecentTurn(String role, String content) {}

    interface RecentTurnsProvider {
        List<RecentTurn> fetchRecentTurns(UUID tenantId, UUID actorId, UUID sessionId, int limit);
    }
}
