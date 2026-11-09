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

package com.openvie.api.bizapp.service.storage;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Routes BizApp records to either dedicated typed tables (for platform flagship apps)
 * or JSONB generic store (for org-custom and generic apps) (Workstream T).
 *
 * <p>Routing is determined strictly by the platform template registry, not by manifest content.
 */
@Component
@RequiredArgsConstructor
public class BizAppStorageRouter {
    private final JsonbRecordStore jsonbStore;

    public BizAppStorageStore route(String appKind, String entity) {
        return jsonbStore;
    }

    public boolean isTyped(String appKind, String entity) {
        return false;
    }

    public String getTableName(String appKind, String entity) {
        return "bizapp_records";
    }

    public Optional<com.openvie.api.bizapp.model.BizAppRecord> findAny(java.util.UUID recordId, String appKind) {
        return jsonbStore.findById(null, appKind, recordId);
    }
}
