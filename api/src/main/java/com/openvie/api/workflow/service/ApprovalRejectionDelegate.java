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
