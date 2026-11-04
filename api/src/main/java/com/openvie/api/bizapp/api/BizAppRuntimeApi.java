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
import java.util.UUID;

public interface BizAppRuntimeApi {
    List<InstalledAppSummary> listInstalledSummary(UUID tenantId);

    List<MarketplaceTemplateSummary> listMarketplaceSummary(UUID tenantId);

    AppActionOffer offerInstall(UUID tenantId, UUID orgId, String appKind, UUID actorId);

    AppActionOffer offerUninstall(UUID tenantId, String appKind, UUID actorId);

    InstallOutcome executeOffer(UUID tenantId, UUID orgId, UUID actorId, AppActionOffer offer);

    DraftOutcome createDraftFromSession(UUID tenantId, UUID orgId, UUID actorId, UUID sessionId, String hint);

    List<UUID> listProjectedDocumentIds(UUID tenantId, String appKind);

    record InstalledAppSummary(
            UUID id,
            String kind,
            String name,
            int version,
            String description,
            List<String> entities,
            List<String> views,
            String status
    ) {
        public InstalledAppSummary {
            entities = entities == null ? List.of() : List.copyOf(entities);
            views = views == null ? List.of() : List.copyOf(views);
        }
    }

    record MarketplaceTemplateSummary(
            String kind,
            String name,
            int version,
            String description,
            List<String> entities,
            List<String> views,
            String templateRef
    ) {
        public MarketplaceTemplateSummary {
            entities = entities == null ? List.of() : List.copyOf(entities);
            views = views == null ? List.of() : List.copyOf(views);
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
