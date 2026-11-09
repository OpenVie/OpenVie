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

package com.openvie.api.bizapp.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppConnectorJob;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppConnectorJobRepository;
import com.openvie.api.bizapp.service.BizAppRuntime;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import com.openvie.api.common.enums.LogAction;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One connector job end to end: parse the block, run the protocol adapter,
 * merge the legacy {@code responseMap} into {@code runtime.external}, apply the
 * typed sink, then audit and meter the attempt.
 *
 * <p>Provider failures surface as {@link ConnectorException}; persistence
 * happens in the caller's completion transaction, so sink writes and the job
 * status commit together.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-CONNECTOR")
public class BizAppConnectorExecutor {

    private static final int MAX_EXTRACTED_CHARS = 200;
    private static final int MAX_EXTRACTED_KEYS = 8;

    private final BizAppConnectorSpecParser specParser;
    private final ConnectorAdapterRegistry adapterRegistry;
    private final BizAppConnectorSink sink;
    private final BizAppConnectorJobRepository jobRepository;
    private final BizAppStorageRouter storageRouter;
    private final BizAppRuntime runtime;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Runs one attempt. Provider/sink failures propagate as
     * {@link ConnectorException}; the worker audits them after the attempt
     * transaction rolls back, because {@code AFTER_COMMIT} audit listeners
     * never fire for a rollback.
     */
    public ConnectorOutcome execute(BizAppInstallation installation, Manifest manifest,
            Block block, BizAppRecord record, UUID actorId, UUID jobId) {
        BizAppConnectorSpec spec = specParser.parse(block);
        ConnectorContext context = new ConnectorContext(
                installation.getOrgId(),
                installation.getTenantId(),
                installation.getId(),
                actorId,
                jobId,
                record.getAppKind(),
                record.getEntity(),
                record.getId(),
                record.getPayload(),
                installation.getConfig());

        long started = System.nanoTime();
        if (!"ACTIVE".equals(record.getStatus())) {
            throw ConnectorException.permanent("record is not active");
        }
        ConnectorAdapter adapter = adapterRegistry.adapter(spec.protocol())
                .orElseThrow(() -> ConnectorException.permanent(
                        "no connector adapter for protocol " + spec.protocol()));
        ConnectorResponse response = adapter.execute(spec, context);
        Map<String, Object> extracted = extractResponse(spec.responseMap(), response.body());
        if (!extracted.isEmpty()) {
            mergeRuntime(record, block.id(), extracted);
        }
        boolean written = sink.apply(installation, manifest, spec.sink(), response, record, actorId);
        storageRouter.route(record.getAppKind(), record.getEntity()).save(record);
        finish(block, record, actorId, jobId, spec, response.status(), response.host(),
                "SUCCEEDED", started, null);
        return new ConnectorOutcome(written ? "wrote " + block.id() + " sink" : "no mapped values");
    }

    /**
     * Publishes the failure audit outside the rolled-back attempt transaction.
     * Called by the worker after rollback so a permanent/retryable failure is
     * still visible in the audit trail, with attempt and duration.
     */
    public void auditFailure(Block block, BizAppRecord record, UUID actorId, UUID jobId,
            long startedNanos, ConnectorException error) {
        BizAppConnectorSpec spec = specParser.parse(block);
        BizAppConnectorJob job = jobId == null ? null : jobRepository.findById(jobId).orElse(null);
        boolean terminal = !error.retryable()
                || (job != null && job.getAttempt() + 1 >= job.getMaxAttempts());
        finish(block, record, actorId, jobId, spec, 0, null,
                terminal ? "DEAD" : "RETRY", startedNanos, error);
    }

    @SuppressWarnings("unchecked")
    private void mergeRuntime(BizAppRecord record, String blockId, Map<String, Object> extracted) {
        Map<String, Object> runtimeState = new LinkedHashMap<>(
                record.getRuntime() == null ? Map.of() : record.getRuntime());
        Map<String, Object> external = new LinkedHashMap<>();
        if (runtimeState.get("external") instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> external.put(String.valueOf(key), value));
        }
        external.put(blockId, extracted);
        runtimeState.put("external", external);
        record.setRuntime(runtimeState);
    }

    private void finish(Block block, BizAppRecord record, UUID actorId, UUID jobId,
            BizAppConnectorSpec spec, int status, String host, String outcome,
            long startedNanos, ConnectorException error) {
        long durationMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        int attempt = 0;
        if (jobId != null) {
            attempt = jobRepository.findById(jobId).map(BizAppConnectorJob::getAttempt).orElse(0) + 1;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("app", record.getAppKind());
        metadata.put("block", block.id());
        metadata.put("protocol", spec.protocol());
        metadata.put("outcome", outcome);
        metadata.put("attempt", attempt);
        metadata.put("durationMs", durationMs);
        if (status > 0) {
            metadata.put("status", status);
        }
        if (host != null) {
            metadata.put("host", host);
        }
        if (jobId != null) {
            metadata.put("jobId", jobId.toString());
        }
        if (error != null) {
            metadata.put("error", String.valueOf(error.getMessage()));
        }
        runtime.audit(record.getTenantId(), actorId, LogAction.BIZAPP_EXTERNAL_CALL,
                "bizapp_record", record.getId(), metadata);
        meterRegistry.counter("bizapp_connector_runs_total",
                "protocol", spec.protocol(), "outcome", outcome).increment();
        meterRegistry.timer("bizapp_connector_duration", "protocol", spec.protocol())
                .record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        log.debug("connector {} {} on record {} -> {}", spec.protocol(), block.id(),
                record.getId(), outcome);
    }

    /** Legacy {@code responseMap} extraction: string values, 200-char cap, max 8 keys. */
    private Map<String, Object> extractResponse(Map<String, String> responseMap, String body) {
        if (body == null || body.isBlank() || responseMap.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            int count = 0;
            for (Map.Entry<String, String> entry : responseMap.entrySet()) {
                if (count >= MAX_EXTRACTED_KEYS) {
                    break;
                }
                JsonNode node = root.at(entry.getValue());
                if (!node.isMissingNode()) {
                    String text = node.asText();
                    if (text.length() > MAX_EXTRACTED_CHARS) {
                        text = text.substring(0, MAX_EXTRACTED_CHARS);
                    }
                    result.put(entry.getKey(), text);
                    count++;
                }
            }
        } catch (Exception exception) {
            log.warn("Failed to parse response for responseMap extraction: {}", exception.getMessage());
        }
        return result;
    }
}
