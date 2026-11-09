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

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Guards the approval presets offered by the Composer: every selectable
 * {@code bpmnTemplate} must parse as BPMN 2.0 and expose the task shapes the
 * approval inbox relies on.
 */
class BizAppApprovalTemplatesTest {

    @Test
    void singleLevelPresetParsesWithOneApprovalTask() throws Exception {
        BpmnModel model = parse("processes/bizapp-single-approval.bpmn20.xml");
        Process process = model.getProcessById("bizappSingleApproval");

        assertNotNull(process);
        assertEquals(List.of("reviewRecord"), taskKeys(process));
    }

    @Test
    void twoLevelPresetParsesWithFirstAndFinalApprovalTasks() throws Exception {
        BpmnModel model = parse("processes/bizapp-two-level-approval.bpmn20.xml");
        Process process = model.getProcessById("bizappTwoLevelApproval");

        assertNotNull(process);
        assertEquals(List.of("level1Approval", "level2Approval"), taskKeys(process));
        assertEquals(List.of("${candidateRole}"),
                ((UserTask) process.getFlowElement("level1Approval")).getCandidateGroups());
        assertEquals(List.of("ORG_OWNER"),
                ((UserTask) process.getFlowElement("level2Approval")).getCandidateGroups());
    }

    private BpmnModel parse(String resource) throws Exception {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(resource);
        assertNotNull(stream, "missing bundled template " + resource);
        byte[] xml;
        try (stream) {
            xml = stream.readAllBytes();
        }
        return new BpmnXMLConverter().convertToBpmnModel(
                () -> new java.io.ByteArrayInputStream(xml), true, false);
    }

    private List<String> taskKeys(Process process) {
        return process.findFlowElementsOfType(UserTask.class).stream()
                .map(UserTask::getId)
                .toList();
    }
}
