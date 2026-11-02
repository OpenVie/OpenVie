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

import lombok.RequiredArgsConstructor;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Terminal delegate of the BizApp approval template. Flowable executes it
 * inside the process transaction; the delegate writes the decision back to
 * {@code bizapp_records.runtime} and raises the {@code bizapp.<kind>.record.decided}
 * lifecycle event, which lands in the webhook outbox after commit.
 *
 * <p>Variable contract: {@code recordId, appKind, entity, decision, decidedBy}.
 */
@Component("bizAppRecordDecisionDelegate")
@RequiredArgsConstructor
public class BizAppRecordDecisionDelegate implements JavaDelegate {

    private final BizAppRecordService recordService;

    @Override
    public void execute(DelegateExecution execution) {
        Object recordId = execution.getVariable("recordId");
        Object appKind = execution.getVariable("appKind");
        Object entity = execution.getVariable("entity");
        Object decision = execution.getVariable("decision");
        Object decidedBy = execution.getVariable("decidedBy");
        if (recordId == null || appKind == null || entity == null) {
            return;
        }
        Map<String, Object> decisionState = new LinkedHashMap<>();
        decisionState.put("processId", execution.getProcessInstanceId());
        decisionState.put("decision", decision == null ? null : String.valueOf(decision));
        decisionState.put("decidedAt", java.time.LocalDateTime.now().toString());
        if (decidedBy != null) {
            decisionState.put("decidedBy", String.valueOf(decidedBy));
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("entity", String.valueOf(entity));
        event.put("decision", decisionState.get("decision"));
        recordService.applyEngineDecision(
                UUID.fromString(String.valueOf(recordId)),
                String.valueOf(appKind),
                decisionState,
                event);
    }
}
