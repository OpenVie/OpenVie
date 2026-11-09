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

package com.openvie.api.bizapp.service;

import com.openvie.api.bizapp.BizAppConnectorProperties;
import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.connector.BizAppConnectorSpec;
import com.openvie.api.bizapp.connector.BizAppConnectorSpecParser;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppConnectorJob;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppConnectorJobRepository;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.bizapp.service.block.ExternalCallBlock;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Poll scheduler for connector blocks declaring {@code schedule}: enumerates
 * the target entity, filters by the declared {@code select} values, and
 * enqueues at most {@code limit} jobs per block per scan.
 *
 * <p>A record is skipped while its latest job is pending/running, and for one
 * {@code every} interval after a success or a terminal failure; so a fixed
 * credential is picked up on the next scheduled poll, not on every scan.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-CONNECTOR")
public class BizAppConnectorScheduler {

    private static final int MAX_BLOCKS_PER_SCAN = 20;
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 10;

    private final BizAppProperties properties;
    private final BizAppConnectorProperties connectorProperties;
    private final BizAppConnectorJobService jobService;
    private final BizAppConnectorSpecParser specParser;
    private final BizAppConnectorJobRepository jobRepository;
    private final BizAppInstallationRepository installationRepository;
    private final BizAppManifestParser manifestParser;
    private final BizAppStorageRouter storageRouter;

    @Scheduled(fixedDelayString = "${app.bizapps.connectors.scan-delay-ms:15000}")
    public void scanSchedules() {
        if (!properties.enabled() || !connectorProperties.enabled()) {
            return;
        }
        for (BizAppInstallation installation : installationRepository.findByStatus("ACTIVE")) {
            try {
                scan(installation);
            } catch (RuntimeException exception) {
                log.warn("connector schedule scan failed for installation {}: {}",
                        installation.getId(), exception.getMessage());
            }
        }
    }

    private void scan(BizAppInstallation installation) {
        Manifest manifest = manifestParser.parse(installation.getManifest());
        if (manifest.blocks() == null) {
            return;
        }
        int scheduledBlocks = 0;
        for (Block block : manifest.blocks()) {
            if (scheduledBlocks >= MAX_BLOCKS_PER_SCAN) {
                break;
            }
            if (!ExternalCallBlock.TYPE.equals(block.type())) {
                continue;
            }
            BizAppConnectorSpec spec;
            try {
                spec = specParser.parse(block);
            } catch (RuntimeException invalid) {
                log.warn("connector block {} of installation {} is invalid: {}",
                        block.id(), installation.getId(), invalid.getMessage());
                continue;
            }
            if (spec.schedule() == null) {
                continue;
            }
            scheduledBlocks++;
            scanBlock(installation, block, spec.schedule());
        }
    }

    private void scanBlock(BizAppInstallation installation, Block block,
            BizAppConnectorSpec.Schedule schedule) {
        int enqueued = 0;
        for (int page = 0; page < MAX_PAGES && enqueued < schedule.limit(); page++) {
            Page<BizAppRecord> rows = storageRouter.route(installation.getKind(), schedule.entity())
                    .list(installation.getTenantId(), installation.getKind(), schedule.entity(),
                            false, PageRequest.of(page, PAGE_SIZE));
            for (BizAppRecord record : rows.getContent()) {
                if (!matches(record.getPayload(), schedule.select())) {
                    continue;
                }
                if (isFresh(installation.getId(), block.id(), record.getId(), schedule.every())) {
                    continue;
                }
                jobService.enqueue(installation.getTenantId(), installation.getOrgId(),
                        installation.getKind(), schedule.entity(), record.getId(), block.id(),
                        installation.getInstalledBy(), "SCHEDULE");
                enqueued++;
                if (enqueued >= schedule.limit()) {
                    break;
                }
            }
            if (!rows.hasNext()) {
                break;
            }
        }
        if (enqueued > 0) {
            log.debug("connector block {} of installation {} queued {} job(s)",
                    block.id(), installation.getId(), enqueued);
        }
    }

    private boolean matches(Map<String, Object> payload, Map<String, Object> select) {
        if (select.isEmpty()) {
            return true;
        }
        if (payload == null) {
            return false;
        }
        for (Map.Entry<String, Object> entry : select.entrySet()) {
            if (!String.valueOf(payload.get(entry.getKey()))
                    .equals(String.valueOf(entry.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private boolean isFresh(UUID installationId, String blockId, UUID recordId, Duration every) {
        BizAppConnectorJob latest = jobRepository
                .findFirstByInstallationIdAndBlockIdAndRecordIdOrderByCreatedAtDesc(
                        installationId, blockId, recordId)
                .orElse(null);
        if (latest == null) {
            return false;
        }
        if ("PENDING".equals(latest.getStatus()) || "RUNNING".equals(latest.getStatus())) {
            return true;
        }
        // SUCCEEDED and DEAD both wait for the next poll interval; a dead
        // credential must not re-enqueue on every 15s scan.
        if (!"SUCCEEDED".equals(latest.getStatus()) && !"DEAD".equals(latest.getStatus())) {
            return false;
        }
        return latest.getCreatedAt() != null
                && latest.getCreatedAt().isAfter(LocalDateTime.now().minus(every));
    }
}
