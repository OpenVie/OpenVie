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

package com.openvie.api.workflow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * BPMN service task "Score document sensitivity". Computes the process
 * variables the exclusive gateway routes on: {@code sensitive} from the
 * shared policy, {@code approvalLevel} as the level decision (sensitive or
 * shared/public target → 2), and {@code policyVersion} so the decision is
 * attributable to exactly one published vocabulary revision.
 */
@Component("sensitivityScoringDelegate")
@RequiredArgsConstructor
@Slf4j(topic = "WORKFLOW")
public class SensitivityScoringDelegate implements JavaDelegate {

    private final SensitivityPolicyService policy;

    @Override
    public void execute(DelegateExecution execution) {
        String fileName = (String) execution.getVariable("documentFileName");
        String publishTarget = (String) execution.getVariable("publishTarget");

        boolean sensitive = policy.isSensitive(fileName);
        int level = policy.decisionLevel(sensitive, publishTarget);

        execution.setVariable("sensitive", sensitive);
        execution.setVariable("approvalLevel", level);
        execution.setVariable("policyVersion", policy.getPolicyVersion());

        log.info("Sensitivity scored: documentId={}, sensitive={}, level={}, version={}",
                execution.getVariable("documentId"), sensitive, level, policy.getPolicyVersion());
    }
}
