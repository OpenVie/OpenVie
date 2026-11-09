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

package com.openvie.api.bizapp.service.knowledge;

import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.bizapp.service.BizAppManifestParser;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import com.openvie.api.common.event.BizAppLifecycleEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asynchronous event bridge triggering knowledge projection on record changes (Workstream D).
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-PROJECTION-LISTENER")
public class BizAppProjectionListener {

    /** Event data keys that carry the acting user for projection attribution. */
    private static final List<String> ACTOR_KEYS =
            List.of("created_by", "updated_by", "decided_by", "submitted_by", "archived_by");

    private final BizAppKnowledgeProjector projector;
    private final BizAppInstallationRepository installationRepository;
    private final BizAppManifestParser parser;
    private final BizAppStorageRouter storageRouter;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBizAppEvent(BizAppLifecycleEvent event) {
        if (event == null || event.getTenantId() == null || event.getSourceId() == null) {
            return;
        }

        String eventType = event.getEventType();
        if (eventType == null || !eventType.startsWith("bizapp.")) {
            return;
        }

        // Expected format: bizapp.<kind>.<entity> or bizapp.<kind>.record.<created|updated|decided>
        String[] parts = eventType.split("\\.");
        if (parts.length < 2) {
            return;
        }
        String appKind = parts[1];

        installationRepository.findByTenantIdAndKindAndStatus(event.getTenantId(), appKind, "ACTIVE")
                .ifPresent(installation -> {
                    var manifest = parser.parse(installation.getManifest());
                    UUID recordId = event.getSourceId();
                    UUID actorId = actorFor(event, installation);

                    if (manifest.entities() != null) {
                        for (var e : manifest.entities()) {
                            storageRouter.route(appKind, e.name()).findById(event.getTenantId(), appKind, recordId)
                                    .ifPresent(record -> {
                                        projector.projectRecord(event.getTenantId(), actorId, installation, manifest, record);
                                        projector.refreshDigest(event.getTenantId(), actorId, installation, manifest);
                                    });
                        }
                    }
                });
    }

    /**
     * Projection writes a {@code documents} row whose {@code uploaded_by} is a
     * foreign key to {@code users(id)}, so the tenant id used previously made
     * every ingest fail and left records in the FAILED knowledge state. Prefer
     * the acting user carried by the event, else the installer.
     */
    private UUID actorFor(BizAppLifecycleEvent event, BizAppInstallation installation) {
        Map<String, Object> data = event.getData() == null ? Map.of() : event.getData();
        for (String key : ACTOR_KEYS) {
            UUID actor = asUuid(data.get(key));
            if (actor != null) {
                return actor;
            }
        }
        return installation.getInstalledBy();
    }

    private UUID asUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return UUID.fromString(text.trim());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
