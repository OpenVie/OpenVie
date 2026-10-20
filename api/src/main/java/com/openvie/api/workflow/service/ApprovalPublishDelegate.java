package com.openvie.api.workflow.service;

import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * BPMN service task "Record approved state" on the approval path.
 */
@Component("approvalPublishDelegate")
@Slf4j(topic = "WORKFLOW")
public class ApprovalPublishDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        execution.setVariable("documentStatus", "APPROVED");
        log.info("Approval completed for process {}", execution.getProcessInstanceId());
    }
}
