package com.openvie.api.workflow.service;

import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * BPMN service task "Record rejected state" on the rejection path. The
 * rejection reason is already stored as the {@code rejectReason} process
 * variable so it stays with the process history.
 */
@Component("approvalRejectionDelegate")
@Slf4j(topic = "WORKFLOW")
public class ApprovalRejectionDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        execution.setVariable("documentStatus", "REJECTED");
        if (execution.getVariable("rejectReason") == null) {
            execution.setVariable("rejectReason", "");
        }
        log.info("Rejection recorded for process {}", execution.getProcessInstanceId());
    }
}
