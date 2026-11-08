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

import com.openvie.api.bizapp.connector.BizAppConnectorSpec;
import com.openvie.api.bizapp.connector.BizAppConnectorSpecParser;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppConnectorJob;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.repository.BizAppConnectorJobRepository;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.bizapp.service.block.ExternalCallBlock;
import com.openvie.api.common.exception.custom.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Durable queue for {@code external_call}: enqueue inside the writing
 * transaction, claim with {@code SKIP LOCKED}, and fold every attempt into an
 * exponential backoff capped at ten minutes.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-CONNECTOR")
public class BizAppConnectorJobService {

    private static final long MAX_BACKOFF_MS = 600_000L;

    private final BizAppConnectorJobRepository jobRepository;
    private final BizAppInstallationRepository installationRepository;
    private final BizAppManifestParser manifestParser;
    private final BizAppConnectorSpecParser specParser;
    private final TransactionTemplate transactions;

    /**
     * Records one job against the ACTIVE installation's frozen manifest. Any
     * failure here is a manifest/lifecycle problem, so it propagates and rolls
     * the triggering record write back (the app cannot do what it declares).
     */
    public UUID enqueue(UUID tenantId, UUID orgId, String appKind, String entity,
            UUID recordId, String blockId, UUID actorId, String triggerKind) {
        BizAppInstallation installation = installationRepository
                .findByTenantIdAndKindAndStatus(tenantId, appKind, "ACTIVE")
                .orElseThrow(() -> new BadRequestException("app " + appKind + " is not installed"));
        Manifest manifest = manifestParser.parse(installation.getManifest());
        Block block = manifest.block(blockId)
                .orElseThrow(() -> new BadRequestException(
                        "block " + blockId + " is not declared in the installed manifest"));
        if (!ExternalCallBlock.TYPE.equals(block.type())) {
            throw new BadRequestException("block " + blockId + " is not an external_call block");
        }
        BizAppConnectorSpec spec = specParser.parse(block);

        BizAppConnectorJob job = new BizAppConnectorJob();
        job.setOrgId(installation.getOrgId());
        job.setTenantId(installation.getTenantId());
        job.setInstallationId(installation.getId());
        job.setAppKind(installation.getKind());
        job.setEntity(entity);
        job.setRecordId(recordId);
        job.setBlockId(blockId);
        job.setTriggerKind(triggerKind);
        job.setStatus("PENDING");
        job.setAttempt(0);
        job.setMaxAttempts(spec.retry().maxAttempts());
        job.setBackoffMs(spec.retry().backoff().toMillis());
        job.setNextAttemptAt(LocalDateTime.now());
        BizAppConnectorJob saved = jobRepository.save(job);
        log.debug("queued connector job {} for {}/{}/{} ({})", saved.getId(), appKind, entity,
                blockId, triggerKind);
        return saved.getId();
    }

    /** Locks and marks due jobs RUNNING; returns the ids this worker now owns. */
    public List<UUID> claim(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<UUID> claimed = transactions.execute(status -> {
            List<UUID> ids = jobRepository.claimDueIds(limit);
            List<UUID> running = new ArrayList<>();
            for (UUID id : ids) {
                jobRepository.findById(id).ifPresent(job -> {
                    job.setStatus("RUNNING");
                    jobRepository.save(job);
                    running.add(id);
                });
            }
            return running;
        });
        return claimed == null ? List.of() : claimed;
    }

    public void succeed(UUID jobId) {
        transactions.executeWithoutResult(status -> jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus("SUCCEEDED");
            job.setLastError(null);
            jobRepository.save(job);
        }));
    }

    /**
     * Folds one failed attempt into the job: terminal when the error is
     * permanent or the budget is spent, otherwise PENDING with exponential
     * backoff (attempt 1 waits one base interval).
     */
    public void fail(UUID jobId, int httpStatus, String error, boolean retryable) {
        transactions.executeWithoutResult(status -> jobRepository.findById(jobId).ifPresent(job -> {
            int attempt = job.getAttempt() + 1;
            job.setAttempt(attempt);
            job.setLastError(error);
            if (!retryable || attempt >= job.getMaxAttempts()) {
                job.setStatus("DEAD");
                log.warn("connector job {} dead after {} attempt(s): {}", jobId, attempt, error);
            } else {
                long delay = Math.min(job.getBackoffMs() * (1L << (attempt - 1)), MAX_BACKOFF_MS);
                job.setStatus("PENDING");
                job.setNextAttemptAt(LocalDateTime.now().plusNanos(delay * 1_000_000L));
                log.info("connector job {} retrying in {} ms (attempt {}): {}", jobId, delay, attempt, error);
            }
            jobRepository.save(job);
        }));
    }
}
