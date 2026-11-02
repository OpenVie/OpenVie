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

import com.openvie.api.bizapp.model.BizAppRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/**
 * Storage adapter interface decoupling BizApp service operations from physical storage
 * (JSONB records vs typed tables).
 */
public interface BizAppStorageStore {

    Optional<BizAppRecord> findById(UUID tenantId, String appKind, UUID recordId);

    Page<BizAppRecord> list(UUID tenantId, String appKind, String entity, boolean includeArchived, Pageable pageable);

    BizAppRecord save(BizAppRecord record);
}
