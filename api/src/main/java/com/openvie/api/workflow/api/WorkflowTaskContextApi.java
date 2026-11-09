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

package com.openvie.api.workflow.api;

import java.util.Map;
import java.util.UUID;

/**
 * Optional display context for a workflow task backed by a BizApp record.
 * Workflow owns the task engine; BizApp owns the record labels and payload.
 * Keeping this behind an interface avoids a workflow → BizApp module cycle.
 */
public interface WorkflowTaskContextApi {

    record TaskContext(String title, String summary, Map<String, String> fields) {
    }

    /**
     * Returns display context for one active BizApp record, or {@code null}
     * when the record/app is not visible in the workspace.
     */
    TaskContext describe(UUID workspaceId, String appKind, String entity, UUID recordId);
}
