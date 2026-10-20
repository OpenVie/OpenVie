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
