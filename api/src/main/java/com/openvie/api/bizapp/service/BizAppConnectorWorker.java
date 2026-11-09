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
import com.openvie.api.bizapp.connector.BizAppConnectorExecutor;
import com.openvie.api.bizapp.connector.ConnectorException;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppConnectorJob;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppConnectorJobRepository;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.bizapp.repository.BizAppRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Connector queue drain: claims due jobs, runs them after the triggering
 * transaction committed, and folds the outcome into the job row.
 *
 * <p>The executor and the completion write share one transaction so a sink row
 * and the job status can never disagree. The scheduled method never lets an
 * exception escape: one poisoned job must not stop the queue.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-CONNECTOR")
public class BizAppConnectorWorker {

    private final BizAppProperties properties;
    private final BizAppConnectorProperties connectorProperties;
    private final BizAppConnectorJobService jobService;
    private final BizAppConnectorExecutor executor;
    private final BizAppConnectorJobRepository jobRepository;
    private final BizAppInstallationRepository installationRepository;
    private final BizAppRecordRepository recordRepository;
    private final BizAppManifestParser manifestParser;
    private final TransactionTemplate transactions;

    @Scheduled(fixedDelayString = "${app.bizapps.connectors.worker-delay-ms:5000}")
    public void processDueJobs() {
        if (!properties.enabled() || !connectorProperties.enabled()) {
            return;
        }
        List<UUID> claimed;
        try {
            claimed = jobService.claim(connectorProperties.batchSize());
        } catch (RuntimeException exception) {
            log.warn("connector job claim failed: {}", exception.getMessage());
            return;
        }
        for (UUID jobId : claimed) {
            try {
                process(jobId);
            } catch (RuntimeException unexpected) {
                log.warn("connector job {} crashed outside its transaction: {}", jobId,
                        unexpected.getMessage());
            }
        }
    }

    private void process(UUID jobId) {
        Loaded loaded = null;
        long startedNanos = System.nanoTime();
        try {
            loaded = load(jobId);
            Loaded target = loaded;
            transactions.executeWithoutResult(status -> {
                executor.execute(target.installation(), target.manifest(), target.block(),
                        target.record(), target.actorId(), jobId);
                jobService.succeed(jobId);
            });
        } catch (ConnectorException exception) {
            auditFailure(loaded, jobId, startedNanos, exception);
            jobService.fail(jobId, 0, exception.getMessage(), exception.retryable());
        } catch (RuntimeException exception) {
            log.warn("connector job {} failed: {}", jobId, exception.getMessage());
            auditFailure(loaded, jobId, startedNanos,
                    ConnectorException.retryable(String.valueOf(exception.getMessage())));
            jobService.fail(jobId, 0, String.valueOf(exception.getMessage()), true);
        }
    }

    /** Loads everything one attempt needs; missing pieces are permanent failures. */
    private Loaded load(UUID jobId) {
        BizAppConnectorJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> ConnectorException.permanent(
                        "connector job " + jobId + " no longer exists"));
        BizAppInstallation installation = installationRepository
                .findById(job.getInstallationId())
                .orElseThrow(() -> ConnectorException.permanent(
                        "connector job " + jobId + " has no installation " + job.getInstallationId()));
        if (!installation.isActive()) {
            throw ConnectorException.permanent(
                    "connector job " + jobId + " belongs to an inactive installation");
        }
        Manifest manifest = manifestParser.parse(installation.getManifest());
        Block block = manifest.block(job.getBlockId())
                .orElseThrow(() -> ConnectorException.permanent(
                        "connector job " + jobId + " has no block " + job.getBlockId()
                                + " in the installed manifest"));
        BizAppRecord record = recordRepository.findById(job.getRecordId())
                .orElseThrow(() -> ConnectorException.permanent(
                        "connector job " + jobId + " has no record " + job.getRecordId()));
        UUID actorId = record.getUpdatedBy() != null
                ? record.getUpdatedBy() : record.getCreatedBy();
        return new Loaded(installation, manifest, block, record, actorId);
    }

    /** Failure audits must run after rollback or AFTER_COMMIT listeners never fire. */
    private void auditFailure(Loaded loaded, UUID jobId, long startedNanos,
            ConnectorException error) {
        if (loaded == null) {
            return;
        }
        try {
            executor.auditFailure(loaded.block(), loaded.record(), loaded.actorId(), jobId,
                    startedNanos, error);
        } catch (RuntimeException auditError) {
            log.warn("connector job {} failure audit skipped: {}", jobId, auditError.getMessage());
        }
    }

    private record Loaded(BizAppInstallation installation, Manifest manifest, Block block,
            BizAppRecord record, UUID actorId) {
    }
}
